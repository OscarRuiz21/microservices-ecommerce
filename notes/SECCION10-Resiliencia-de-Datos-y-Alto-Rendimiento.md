# Seccion 10: Resiliencia de Datos y Alto Rendimiento

> Notas del curso de Microservicios — Virtual Threads, Transactional Outbox y Message Relayer

---

## Objetivo de la seccion

Pasar de un sistema que **depende de la disponibilidad del broker** para completar ventas a uno que **garantiza zero data loss** independientemente del estado de RabbitMQ. Ademas, se activa la concurrencia masiva con hilos virtuales de Java 21:

- **Virtual Threads (Project Loom):** Activacion de hilos livianos en los puntos criticos (Gateway y Notification) para manejar miles de conexiones simultaneas sin agotar recursos
- **El problema de la dependencia:** Si RabbitMQ cae, el `@Transactional` hace rollback y la venta se pierde. El negocio se detiene por una dependencia de infraestructura
- **Transactional Outbox:** Se guarda el evento en la misma transaccion que la orden. Si la BD funciona, la venta se completa — siempre
- **Message Relayer:** Un proceso scheduler que lee los eventos pendientes del outbox y los entrega al broker cuando este disponible
- **Coreografia de eventos:** Se separan `OrderPlacedEvent` y `OrderConfirmedEvent` como tipos distintos para evitar bucles infinitos en la saga

---

## 1. Virtual Threads — Project Loom en Spring Boot 4

### Concepto

Los **Virtual Threads** (Hilos Virtuales) de Java 21 son hilos extremadamente livianos gestionados por la JVM en lugar del sistema operativo. Mientras un hilo del SO consume ~1MB de stack, un hilo virtual consume ~pocos KB. Esto permite crear **miles o millones de hilos concurrentes** sin agotar la memoria.

### 1.1 El problema: El Gateway como cuello de botella

El API Gateway es la **puerta de entrada unica** a todo el sistema. Si se queda sin hilos del sistema operativo por peticiones concurrentes, **todo el sistema parece caido** — aunque los microservicios internos esten funcionando perfectamente.

| Escenario | Hilos del SO (tradicional) | Virtual Threads |
|---|---|---|
| 200 usuarios simultaneos | 200 hilos de ~1MB = ~200MB | 200 hilos virtuales = ~pocos KB |
| 10,000 usuarios simultaneos | Fallo: "Too many threads" | 10,000 hilos virtuales sin problemas |
| Hilo bloqueado por I/O | El hilo del SO queda ocupado | La JVM lo libera y reutiliza el carrier thread |

### 1.2 Donde se activan

Se activan en los dos puntos donde el impacto es mayor:

| Servicio | Por que |
|---|---|
| **API Gateway** | Puerta de entrada unica. Cada peticion HTTP del frontend consume un hilo. Con Virtual Threads, la capacidad de concurrencia es practicamente ilimitada |
| **Notification Service** | El envio de correos via SMTP es **lento y bloqueante**. Sin Virtual Threads, un envio de email bloquea el hilo que escucha RabbitMQ, deteniendo el procesamiento de otros mensajes |

### 1.3 Configuracion

Una sola linea en el YAML de cada servicio:

```yaml
spring:
  threads:
    virtual:
      enabled: true   # Habilita Virtual Threads de Project Loom
```

Esta propiedad configura automaticamente el servidor embebido (Tomcat/Netty) para procesar cada peticion en un **hilo virtual independiente**.

**La belleza de Virtual Threads es que son transparentes:** el codigo no cambia ni una linea. La misma logica de negocio, los mismos `@RestController`, los mismos `@RabbitListener`. Solo cambia la capacidad de concurrencia — y se multiplica.

---

## 2. El Problema de la Dependencia del Broker

### Concepto

En la Seccion 9, el metodo `placeOrder()` hacia dos cosas dentro del mismo `@Transactional`:

1. Guardaba la orden en PostgreSQL
2. Publicaba el evento a RabbitMQ via `rabbitTemplate.convertAndSend()`

**El problema:** Si RabbitMQ esta caido, `convertAndSend()` lanza una `AmqpException`. Como esto ocurre dentro del `@Transactional`, Spring hace **rollback de toda la transaccion** — incluyendo el guardado de la orden.

```
Flujo anterior (fragil):

  placeOrder() [@Transactional]
       │
       ├── orderRepository.save(order)        ← se guarda en PostgreSQL
       ├── rabbitTemplate.convertAndSend(...)  ← FALLA si RabbitMQ esta caido
       │
       └── ROLLBACK → la orden TAMBIEN se pierde
```

**Resultado:** Si RabbitMQ cae por 5 minutos, durante esos 5 minutos **ningun cliente puede comprar**. El negocio se detiene por una dependencia de infraestructura que no deberia ser critica.

### Por que esto es inaceptable

| Componente | Rol | Deberia poder caerse sin detener ventas? |
|---|---|---|
| **PostgreSQL** | Almacena los datos del negocio | No — si la BD cae, no podemos guardar nada |
| **RabbitMQ** | Enruta mensajes entre servicios | **Si** — la venta deberia completarse y el mensaje enviarse despues |

La base de datos es la **dependencia critica** (sin ella no hay negocio). El broker es una **dependencia operacional** (facilita la comunicacion, pero no deberia bloquear la operacion principal).

---

## 3. Patron Transactional Outbox — La Bandeja de Salida

### Concepto

El Transactional Outbox es un patron arquitectonico que **desacopla el guardado del dato de la entrega del mensaje**. En lugar de enviar el evento directamente a RabbitMQ dentro de la transaccion, se guarda el evento como un registro en una tabla de la misma base de datos.

**La analogia:** Cuando escribes un email sin internet, tu cliente de correo **no falla**. Guarda el email en la "Bandeja de Salida" y lo envia automaticamente cuando recuperas conexion. El Outbox hace exactamente lo mismo con los eventos.

```
Flujo con Outbox (resiliente):

  placeOrder() [@Transactional]
       │
       ├── orderRepository.save(order)          ← se guarda en PostgreSQL
       ├── outboxService.saveOrderPlacedEvent()  ← se guarda en PostgreSQL (misma BD, misma TX)
       │
       └── COMMIT → ambas cosas estan seguras (garantia ACID)

  Si RabbitMQ esta disponible → se intenta enviar inmediatamente
  Si RabbitMQ esta caido → el Message Relayer lo enviara despues
```

**La regla:** Si PostgreSQL dice OK, la venta se completo y el evento esta asegurado. RabbitMQ ya no participa en la transaccion.

### 3.1 La entidad OutboxEvent

```java
@Entity
@Table(name = "outbox_events")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class OutboxEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String aggregateId;   // orderNumber — para relacionar con la orden
    private String type;          // "ORDER_PLACED" — identificador del tipo de evento

    @Column(columnDefinition = "TEXT")
    private String payload;       // JSON completo del OrderPlacedEvent

    private LocalDateTime createdAt;
    private boolean processed;    // false = pendiente, true = ya enviado a RabbitMQ
}
```

| Campo | Para que |
|---|---|
| `aggregateId` | El `orderNumber`. Permite rastrear que evento corresponde a que orden |
| `type` | Identifica el tipo de evento. Util si en el futuro hay multiples tipos de eventos en el outbox |
| `payload` | El JSON serializado del evento completo. El Message Relayer lo deserializa para reenviarlo |
| `processed` | Bandera que indica si ya fue enviado exitosamente a RabbitMQ. Evita envios duplicados |

### 3.2 El repositorio

```java
public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findByProcessedFalse();  // Solo los eventos pendientes de envio
}
```

### 3.3 El servicio OutboxService

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxServiceImpl implements OutboxService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void saveOrderPlacedEvent(OrderPlacedEvent event, boolean isProcessed) {
        String payload = objectMapper.writeValueAsString(event);

        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateId(event.orderNumber())
                .type("ORDER_PLACED")
                .payload(payload)
                .createdAt(LocalDateTime.now())
                .processed(isProcessed)    // true si ya se envio a RabbitMQ, false si no
                .build();

        outboxRepository.save(outboxEvent);
    }

    @Override
    public List<OutboxEvent> getPendingEvents() {
        return outboxRepository.findByProcessedFalse();
    }

    @Override
    public void markAsProcessed(Long id) {
        outboxRepository.findById(id).ifPresent(event -> {
            event.setProcessed(true);
            outboxRepository.save(event);
        });
    }
}
```

El parametro `isProcessed` es clave: si el envio inmediato a RabbitMQ funciona, se guarda como `true` (ya procesado). Si falla, se guarda como `false` (pendiente) para que el Message Relayer lo recoja despues.

### 3.4 El flujo completo en OrderServiceImpl

```java
@Override
@Transactional
public OrderResponse placeOrder(OrderRequest orderRequest, String userId) {
    // ... validaciones ...

    // 1. Guardar la orden en PostgreSQL
    order.setOrderNumber(UUID.randomUUID().toString());
    order.setStatus(OrderStatus.PLACED);
    Order savedOrder = orderRepository.save(order);

    // 2. Construir el evento
    OrderPlacedEvent event = new OrderPlacedEvent(
            savedOrder.getOrderNumber(), orderRequest.getEmail(), orderItems
    );

    // 3. Intentar enviar a RabbitMQ (best-effort)
    boolean sentToRabbit = false;
    try {
        rabbitTemplate.convertAndSend("order-events", "order.placed", event);
        sentToRabbit = true;
        log.info("Mensaje enviado inmediatamente a RabbitMQ: {}", savedOrder.getOrderNumber());
    } catch (AmqpException e) {
        log.error("RabbitMQ caido. El Outbox asegurara el envio posterior para la orden: {}",
                  savedOrder.getOrderNumber());
    }

    // 4. SIEMPRE guardar en outbox (como procesado o pendiente)
    outboxService.saveOrderPlacedEvent(event, sentToRabbit);

    return orderMapper.toOrderResponse(savedOrder);
}
```

**Los 3 escenarios posibles:**

| Escenario | Que pasa | Resultado |
|---|---|---|
| RabbitMQ OK | Evento enviado inmediatamente + guardado en outbox como `processed=true` | Orden creada, evento entregado al instante |
| RabbitMQ caido | `AmqpException` capturada + guardado en outbox como `processed=false` | Orden creada, evento pendiente en outbox |
| PostgreSQL caido | Rollback de toda la transaccion | Ni la orden ni el outbox se guardan — consistente |

En los 3 escenarios, el sistema es **consistente**. Nunca hay una orden sin su evento, ni un evento sin su orden.

---

## 4. Message Relayer — El Cartero de Mensajes

### Concepto

El Message Relayer es un **proceso scheduler** que corre periodicamente, consulta la tabla `outbox_events` buscando eventos pendientes (`processed=false`), y los envia a RabbitMQ. Es el "cartero" que revisa la bandeja de salida y entrega los mensajes que estaban esperando.

### 4.1 Habilitacion del Scheduler

Para que `@Scheduled` funcione, se necesita `@EnableScheduling` en la clase principal:

```java
@SpringBootApplication
@EnableScheduling
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
```

### 4.2 Implementacion del MessageRelayer

```java
@Component
@Slf4j
@RequiredArgsConstructor
public class MessageRelayer {
    private final RabbitTemplate rabbitTemplate;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 10000)  // Cada 10 segundos
    public void relayMessage() {

        List<OutboxEvent> pendingEvents = outboxService.getPendingEvents();

        if (!pendingEvents.isEmpty()) {
            log.info("Relayer: Detectados {} mensajes pendientes.", pendingEvents.size());

            for (OutboxEvent event : pendingEvents) {
                try {
                    // Deserializar el JSON almacenado de vuelta a OrderPlacedEvent
                    OrderPlacedEvent originalEvent = objectMapper.readValue(
                            event.getPayload(), OrderPlacedEvent.class
                    );

                    // Reenviar a RabbitMQ
                    rabbitTemplate.convertAndSend("order-events", "order.placed", originalEvent);

                    // Marcar como procesado para no reenviarlo
                    outboxService.markAsProcessed(event.getId());
                    log.info("Mensaje recuperado y enviado: {}", event.getAggregateId());

                } catch (JacksonException e) {
                    log.error("Error deserializando evento {}: {}", event.getId(), e.getMessage());
                } catch (AmqpException e) {
                    // RabbitMQ sigue caido — el mensaje queda pendiente para el proximo ciclo
                    log.error("Fallo el reintento para {}: {}", event.getAggregateId(), e.getMessage());
                }
            }
        }
    }
}
```

### 4.3 Ciclo de vida del Relayer

```
Cada 10 segundos:
  ┌───────────────────────────────────────────────────────────┐
  │  1. SELECT * FROM outbox_events WHERE processed = false   │
  │                                                           │
  │  2. Para cada evento pendiente:                           │
  │     a. Deserializar JSON → OrderPlacedEvent               │
  │     b. rabbitTemplate.convertAndSend(...)                  │
  │     c. Si OK → markAsProcessed(id)                        │
  │     d. Si falla → queda pendiente para el proximo ciclo   │
  │                                                           │
  │  3. Repetir en 10 segundos                                │
  └───────────────────────────────────────────────────────────┘
```

**`@Scheduled(fixedDelay = 10000)`**: El `fixedDelay` garantiza que pasan 10 segundos **desde que termina** la ejecucion anterior hasta que empieza la siguiente. Si una ejecucion tarda 3 segundos, la siguiente empieza 10 segundos despues de que termino (no 10 segundos despues de que empezo).

Si RabbitMQ sigue caido durante el ciclo del relayer, el `AmqpException` se captura y el evento queda pendiente. En el siguiente ciclo se vuelve a intentar. Este patron es **auto-curativo**: cuando RabbitMQ vuelve, todos los mensajes pendientes se entregan automaticamente.

---

## 5. Coreografia de Eventos — OrderPlaced vs OrderConfirmed

### El problema del bucle infinito

En la Seccion 9, Inventory Service reenviaba el **mismo** `OrderPlacedEvent` como confirmacion. Esto era problematico porque Order Service escuchaba en `order-confirmed-queue`, pero el tipo del mensaje seguia siendo `OrderPlacedEvent`. Si el routing o el deserializador no distinguia bien los tipos, se podia generar un **bucle infinito** o un procesamiento incorrecto.

### La solucion: Tipos de evento distintos

Se separan los eventos en tipos claramente diferenciados:

| Evento | Quien lo publica | Que significa | Routing Key |
|---|---|---|---|
| `OrderPlacedEvent` | Order Service | Se creo una nueva orden pendiente de validacion | `order.placed` |
| `OrderConfirmedEvent` | Inventory Service | El stock fue validado y descontado exitosamente | `order.confirmed` |
| `OrderCancelledEvent` | Inventory Service | No hay stock o hubo un error tecnico | `order.cancelled` |

### 5.1 Inventory Service — Publica OrderConfirmedEvent (no reenvio)

```java
@RabbitListener(queues = "inventory-queue")
public void handleOrderPlacedEvent(OrderPlacedEvent event) {
    // ... validar y descontar stock ...

    // Crear un evento NUEVO de tipo OrderConfirmedEvent (no reenviar OrderPlacedEvent)
    OrderConfirmedEvent confirmedEvent = new OrderConfirmedEvent(
            event.orderNumber(), event.email()
    );

    rabbitTemplate.convertAndSend("order-events", "order.confirmed", confirmedEvent);
}
```

**Antes:** Se publicaba el mismo `OrderPlacedEvent` como confirmacion. El header `__TypeId__` decia `OrderPlacedEvent`, lo que causaba confusion.

**Ahora:** Se crea un `OrderConfirmedEvent` nuevo con solo los campos necesarios (`orderNumber`, `email`). El tipo es inequivoco.

### 5.2 Order Service — Consume tipos especificos

```java
@RabbitListener(queues = "order-confirmed-queue")
public void handleOrderConfirmed(OrderConfirmedEvent event) {
    if (event.orderNumber() == null) {
        log.error("OrderConfirmedEvent con orderNumber null. Descartando.");
        return;
    }
    orderService.updateOrderStatus(event.orderNumber(), OrderStatus.CONFIRMED);
}

@RabbitListener(queues = "order-cancelled-queue")
public void handleOrderCancelled(OrderCancelledEvent event) {
    if (event.orderNumber() == null) {
        log.error("OrderCancelledEvent con orderNumber null. Descartando.");
        return;
    }
    orderService.updateOrderStatus(event.orderNumber(), OrderStatus.CANCELLED);
}
```

**Validacion de null:** Se agrega una guarda contra eventos malformados o deserializados incorrectamente. Si el `orderNumber` es null, el evento se descarta en lugar de causar un error en cascada.

### 5.3 Notification Service — Mapeo actualizado

El `DefaultClassMapper` se actualiza para mapear los nuevos tipos:

```java
// ANTES (seccion 9): mapeaba OrderPlacedEvent de Inventory como confirmacion
idClassMapping.put("com.ecommerce.inventory_service.event.OrderPlacedEvent",
                   OrderConfirmedEvent.class);

// AHORA (seccion 10): mapea OrderConfirmedEvent directamente
idClassMapping.put("com.ecommerce.inventory_service.event.OrderConfirmedEvent",
                   OrderConfirmedEvent.class);
```

Ahora el tipo que viene en el header `__TypeId__` coincide semanticamente con la clase local. No hay ambiguedad.

### 5.4 Flujo completo de la coreografia

```
Order Service                    Inventory Service              Notification Service
     │                                 │                               │
     │  ──OrderPlacedEvent──>          │                               │
     │  (order.placed)                 │                               │
     │                                 │                               │
     │                           Valida stock                          │
     │                           Descuenta                             │
     │                                 │                               │
     │  <──OrderConfirmedEvent──       │  ──OrderConfirmedEvent──>     │
     │  (order.confirmed)              │  (order.confirmed)            │
     │                                 │                               │
     │  status=CONFIRMED               │                       Envia email
     │                                 │                               │
```

Cada flecha es un **tipo de evento distinto**. No hay reenvio de eventos, no hay ambiguedad de tipos, no hay riesgo de bucles.

---

## Archivos clave de esta seccion

| Archivo | Que hace |
|---|---|
| `order-service/.../OrderServiceApplication.java` | Agrega `@EnableScheduling` para habilitar el Message Relayer |
| `order-service/.../model/OutboxEvent.java` | Entidad JPA con aggregateId, type, payload (JSON), processed |
| `order-service/.../repository/OutboxRepository.java` | `findByProcessedFalse()` — consulta los eventos pendientes |
| `order-service/.../service/OutboxService.java` | Interfaz: saveOrderPlacedEvent, getPendingEvents, markAsProcessed |
| `order-service/.../service/impl/OutboxServiceImpl.java` | Serializa evento a JSON, guarda en outbox, marca como procesado |
| `order-service/.../scheduler/MessageRelayer.java` | Scheduler cada 10s que envia eventos pendientes a RabbitMQ |
| `order-service/.../service/impl/OrderServiceImpl.java` | `placeOrder()` con try/catch de AmqpException y guardado en outbox |
| `order-service/.../listener/OrderEventsListener.java` | Consume `OrderConfirmedEvent` y `OrderCancelledEvent` (tipos separados) con validacion de null |
| `inventory-service/.../listener/OrderEventsListener.java` | Publica `OrderConfirmedEvent` nuevo en lugar de reenviar `OrderPlacedEvent` |
| `notification-service/.../config/RabbitMQConfig.java` | ClassMapper actualizado para mapear `OrderConfirmedEvent` de Inventory |
