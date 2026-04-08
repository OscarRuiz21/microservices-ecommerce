# Seccion 9: Arquitectura Orientada a Eventos & Sagas (RabbitMQ)

> Notas del curso de Microservicios — Mensajeria Asincrona y Consistencia Eventual con RabbitMQ

---

## Objetivo de la seccion

Pasar de una comunicacion sincrona HTTP (donde Order Service llama directamente a Inventory y se bloquea esperando respuesta) a una **arquitectura orientada a eventos** donde:

- Order Service **publica un evento** y sigue su camino sin bloquearse
- Inventory Service **consume el evento**, valida stock, y responde con otro evento
- Notification Service **reacciona a certezas** (confirmacion o cancelacion) para notificar al usuario
- Se implementa el **Patron Saga** con eventos de compensacion para revertir operaciones fallidas
- La **consistencia eventual** reemplaza a la transaccionalidad sincrona, eliminando los problemas de hilos zombie y datos fantasma descubiertos en la Seccion 8

---

## 1. Fundamentos: Mensajeria Asincrona y RabbitMQ

### 1.1 Por que mensajeria — El problema de la comunicacion sincrona

En una arquitectura de microservicios con comunicacion HTTP, cada llamada entre servicios crea un **acoplamiento temporal**: el emisor se bloquea hasta que el receptor responde. Esto genera tres problemas fundamentales:

| Problema | Que pasa |
|---|---|
| **Acoplamiento temporal** | Si Inventory esta caido, Order no puede funcionar. Un servicio arrastra al otro |
| **Latencia acumulada** | Si Order llama a Inventory (200ms) y a Notification (150ms), el usuario espera la suma: 350ms+ |
| **Escalabilidad limitada** | Si Inventory se satura, no puedes absorber picos de demanda — las peticiones se encolan en hilos bloqueados |

La **mensajeria asincrona** rompe este acoplamiento. El emisor deposita un mensaje en un intermediario (broker) y sigue con su vida. El receptor lo procesa cuando puede. No hay espera, no hay bloqueo, no hay dependencia directa.

```
Sincrono (HTTP):                          Asincrono (Mensajeria):
                                          
Order ──HTTP──> Inventory                 Order ──msg──> [Broker] ──msg──> Inventory
  (bloqueado esperando)                     (sigue trabajando)     (procesa cuando puede)
  │                                         │
  └── si Inventory cae, Order cae          └── si Inventory cae, el mensaje espera
```

### 1.2 Arquitectura Orientada a Eventos (EDA)

En una **Event-Driven Architecture**, los servicios no se llaman entre si. En su lugar:

1. Un servicio **publica un evento** que describe algo que ya ocurrio ("Se creo una orden")
2. Los servicios interesados **se suscriben** a ese tipo de evento
3. Cada suscriptor **reacciona** de forma independiente

**El emisor no sabe (ni le importa) quien consume el evento.** Esto es la diferencia fundamental con HTTP: en HTTP le pides algo a alguien especifico; en EDA anuncias que algo paso y cualquiera puede reaccionar.

| Caracteristica | HTTP (Request/Response) | Eventos (Pub/Sub) |
|---|---|---|
| **Direccion** | A → B (punto a punto) | A → Broker → B, C, D... (uno a muchos) |
| **Acoplamiento** | A necesita conocer a B | A no sabe quien escucha |
| **Temporalidad** | Ambos deben estar vivos | El receptor puede estar caido y procesar despues |
| **Flujo** | Sincrono (espera respuesta) | Asincrono (fire and forget) |
| **Escalabilidad** | Limitada por el mas lento | Cada servicio escala independiente |

### 1.3 El Message Broker — El intermediario

Un **Message Broker** es el componente central de la mensajeria. Su trabajo: recibir mensajes de los productores, almacenarlos, enrutarlos y entregarlos a los consumidores. Es el "cartero" del sistema.

Sin broker, los servicios tendrian que conocerse entre si, mantener conexiones directas, y manejar reintentos por su cuenta. El broker centraliza toda esa complejidad.

**RabbitMQ** es un broker de mensajes open-source que implementa el protocolo **AMQP (Advanced Message Queuing Protocol)**. Es el broker mas usado en el ecosistema Spring por su integracion nativa con `spring-boot-starter-amqp`.

#### Beneficios de RabbitMQ

| Beneficio | Que significa |
|---|---|
| **Desacoplamiento** | Los servicios no se conocen entre si. Solo conocen al broker |
| **Escalabilidad** | Puedes agregar mas consumidores sin tocar el productor |
| **Persistencia** | Los mensajes se almacenan en disco. Si el broker reinicia, no se pierden |
| **Confiabilidad** | Acknowledgements garantizan que un mensaje no se pierde hasta ser procesado |
| **Procesamiento asincrono** | El productor no espera. Publica y sigue |
| **Reintentos automaticos** | Si un consumidor falla, el mensaje se reencola o va a la DLQ |

### 1.4 Protocolo AMQP — Por que RabbitMQ no usa HTTP

RabbitMQ no usa HTTP para la comunicacion entre servicios. Usa **AMQP (Advanced Message Queuing Protocol)**, un protocolo binario disenado especificamente para mensajeria.

**La pregunta:** Si ya tenemos HTTP que funciona en toda la web, por que usar otro protocolo?

| Caracteristica | HTTP | AMQP |
|---|---|---|
| **Formato** | Texto (headers legibles, JSON) | Binario (compacto, eficiente) |
| **Conexion** | Abre y cierra por cada request | Conexion persistente con multiplexacion |
| **Canales** | Una conexion = una conversacion | Una conexion = multiples canales simultaneos |
| **Garantias** | Ninguna nativa (si se pierde, se pierde) | Acknowledgements, persistencia, reintentos |
| **Direccion** | Request → Response (sincrono) | Publish → Queue → Consume (asincrono) |

**Las 3 ventajas clave de AMQP sobre HTTP:**

1. **Eficiencia Binaria:** Los mensajes AMQP son binarios, no texto. Menos bytes = mas rendimiento. HTTP envia headers de texto repetitivos en cada request.
2. **Multiplexacion (Canales):** Una sola conexion TCP puede tener multiples canales independientes. HTTP necesita una conexion por cada request-response (o HTTP/2 para multiplexar, pero no es su fuerte).
3. **Garantias de Entrega:** AMQP tiene acknowledgements nativos. El broker sabe si el mensaje fue procesado. HTTP no tiene este concepto — si el cliente no recibe respuesta, no sabe si el servidor proceso o no la peticion.

**Puerto:** AMQP usa el puerto `5672`. La UI de gestion usa HTTP en el `15672`.

**Lenguajes soportados:** Java, Python, Node.js, C#, Go, Ruby, PHP, y practicamente cualquier lenguaje con un cliente AMQP.

### 1.5 Anatomia de RabbitMQ — Los 5 componentes

```
                         RabbitMQ Broker
                    ┌─────────────────────────────────────────┐
                    │                                         │
  Producer ────────>│  Exchange ──(Binding)──> Queue ─────────│───────> Consumer
  (publica)         │  (enruta)   (routing    (almacena)      │         (procesa)
                    │              key)                        │
                    └─────────────────────────────────────────┘
```

| Componente | Que es | Analogia |
|---|---|---|
| **Producer** | El servicio que publica mensajes. No los envia a una cola directamente, sino a un Exchange | La persona que deposita una carta en el correo |
| **Exchange** | Recibe mensajes del producer y decide **a que cola(s)** enviarlos segun reglas de enrutamiento | La oficina de correos que clasifica y enruta las cartas |
| **Binding** | La regla que conecta un Exchange con una Queue. Define "los mensajes con esta routing key van a esta cola" | La instruccion "las cartas con codigo postal 28001 van al buzon de Madrid" |
| **Queue** | Buffer que almacena mensajes hasta que un consumidor los procese. Es FIFO (First In, First Out) | El buzon donde las cartas esperan a ser recogidas |
| **Consumer** | El servicio que lee y procesa mensajes de una cola | La persona que recoge y lee las cartas del buzon |

**Regla fundamental:** El producer **nunca** envia mensajes directamente a una cola. Siempre pasa por un Exchange. El Exchange decide el destino segun el tipo de exchange y las bindings configuradas.

### 1.6 Tipos de Exchange

RabbitMQ ofrece 4 tipos de Exchange, cada uno con una estrategia de enrutamiento diferente:

#### Direct Exchange

Enruta el mensaje a la cola cuyo binding tenga una **routing key exactamente igual** a la del mensaje.

```
Producer ──(routing_key="order.confirmed")──> Direct Exchange
                                                 │
                          binding key="order.confirmed" ──> order-confirmed-queue  ✅
                          binding key="order.cancelled" ──> order-cancelled-queue  ✗
```

**Caso de uso:** Cuando un mensaje debe llegar a exactamente una cola especifica. Como enviar una carta a una direccion exacta.

#### Topic Exchange (el que usamos en esta seccion)

Enruta el mensaje a las colas cuyo binding matchee con **patrones** en la routing key. Los patrones usan:
- `*` (asterisco): Matchea exactamente **una palabra**
- `#` (hash): Matchea **cero o mas palabras**

```
Producer ──(routing_key="order.placed")──> Topic Exchange
                                              │
                       binding key="order.placed"    ──> inventory-queue       ✅
                       binding key="order.confirmed" ──> notification-queue    ✗
                       binding key="order.*"         ──> audit-queue           ✅
                       binding key="order.#"         ──> logging-queue         ✅
```

**Caso de uso:** Cuando necesitas enrutamiento flexible basado en categorias. Es el mas versatil y el estandar para arquitecturas de eventos.

#### Fanout Exchange

Envia el mensaje a **todas las colas** bindeadas, sin importar la routing key. Es un broadcast puro.

```
Producer ──(cualquier key)──> Fanout Exchange
                                  │
                                  ├──> queue-A  ✅
                                  ├──> queue-B  ✅
                                  └──> queue-C  ✅
```

**Caso de uso:** Cuando todo el mundo necesita recibir el mensaje. Ejemplo: logs, metricas, cache invalidation.

#### Headers Exchange

Enruta basandose en los **headers** del mensaje (no en la routing key). Cada binding define que headers debe tener el mensaje para matchear.

**Caso de uso:** Raro. Se usa cuando la logica de enrutamiento es mas compleja que lo que permite una routing key. En la practica, Topic Exchange cubre el 95% de los casos.

### 1.7 Patrones de Mensajeria

#### Point-to-Point (Work Queue)

Un mensaje es procesado por **exactamente un consumidor**. Si hay multiples consumidores en la misma cola, RabbitMQ los balancea con round-robin.

```
Producer ──> Queue ──> Consumer A  (procesa mensaje 1, 3, 5...)
                  └──> Consumer B  (procesa mensaje 2, 4, 6...)
```

**Caso de uso:** Procesamiento distribuido de tareas. Si tienes 10.000 ordenes que validar, puedes escalar a 5 instancias de Inventory y RabbitMQ reparte la carga.

#### Publish/Subscribe (Fan-Out)

Un evento llega a **multiples colas** y cada una tiene su propio consumidor. Cada servicio procesa el mismo evento de forma independiente.

```
Producer ──> Exchange ──> queue-inventory ──> Inventory Service
                     └──> queue-notification ──> Notification Service
                     └──> queue-analytics ──> Analytics Service
```

**Caso de uso:** Cuando un evento debe disparar acciones en multiples servicios. Es el patron que usamos: `order.confirmed` llega tanto a Order Service como a Notification Service.

#### Competing Consumers (Consumo Competitivo)

Variante de Point-to-Point donde multiples instancias del mismo servicio compiten por mensajes de la misma cola. RabbitMQ garantiza que cada mensaje se entrega a **un solo consumidor**.

```
                     ┌──> Notification Instance 1
Queue ──────────────>├──> Notification Instance 2
                     └──> Notification Instance 3
```

**Caso de uso:** Escalabilidad horizontal. Si el envio de correos es lento, levantas 3 instancias de Notification y RabbitMQ reparte los mensajes automaticamente.

### 1.8 Garantias de Entrega y Acknowledgements

RabbitMQ ofrece un sistema de **acknowledgements (ack)** para garantizar que los mensajes no se pierdan:

| Escenario | Que pasa |
|---|---|
| Consumer procesa con exito | Envia `ack` → RabbitMQ elimina el mensaje de la cola |
| Consumer falla (excepcion) | Envia `nack` → RabbitMQ reencola el mensaje o lo envia a la DLQ |
| Consumer muere (crash) | No hay ack → RabbitMQ reencola el mensaje automaticamente |

Spring AMQP maneja los acks automaticamente: si el metodo `@RabbitListener` termina sin excepcion → `ack`. Si lanza excepcion → `nack`.

**Niveles de garantia:**

| Nivel | Significado | Como se logra |
|---|---|---|
| **At-most-once** | El mensaje se entrega 0 o 1 vez. Puede perderse | Auto-ack sin persistencia |
| **At-least-once** | El mensaje se entrega 1 o mas veces. Puede duplicarse | Manual ack + persistencia + reintentos |
| **Exactly-once** | El mensaje se entrega exactamente 1 vez | Extremadamente dificil. Requiere idempotencia en el consumer |

En esta seccion trabajamos con **at-least-once**: mensajes persistentes, colas durables, reintentos automaticos. Los consumidores deben ser **idempotentes** (procesar el mismo mensaje dos veces no debe causar problemas).

### 1.9 RabbitMQ vs Kafka — Cuando usar cada uno

| Criterio | RabbitMQ | Kafka |
|---|---|---|
| **Modelo** | Message Broker (envia y elimina) | Event Log (almacena y reproduce) |
| **Mensajes** | Se borran al ser consumidos | Se almacenan por tiempo configurable |
| **Routing** | Flexible (exchanges, routing keys, bindings) | Por particion de topico |
| **Orden** | Garantizada por cola | Garantizada por particion |
| **Replay** | No (una vez consumido, se fue) | Si (puedes releer desde cualquier offset) |
| **Throughput** | Miles de mensajes/segundo | Millones de mensajes/segundo |
| **Caso ideal** | Tareas, notificaciones, sagas, workflows | Streaming, event sourcing, analytics en tiempo real |
| **Complejidad** | Menor (mas facil de operar) | Mayor (ZooKeeper/KRaft, particiones, consumer groups) |

**Regla practica:** Si necesitas **procesar tareas y olvidar**, usa RabbitMQ. Si necesitas **almacenar el historial de eventos** y poder reproducirlo, usa Kafka.

Para una saga de e-commerce como la nuestra (validar stock, confirmar orden, notificar), RabbitMQ es la eleccion natural.

### 1.10 La Arquitectura Hibrida — HTTP y RabbitMQ coexisten

Una pregunta comun al conocer RabbitMQ: **"Entonces tiramos HTTP a la basura?"** La respuesta es **NO**. HTTP y RabbitMQ coexisten en la misma arquitectura. La clave para elegir es hacerse una pregunta:

> **"Necesito la respuesta YA?"**

| Situacion | Quien gana | Por que |
|---|---|---|
| Consultar el catalogo de productos | **HTTP** | El usuario espera ver la lista ahora mismo. Necesita la respuesta inmediata |
| Buscar una orden por ID | **HTTP** | Es una lectura directa. El dato debe llegar al instante |
| Login / Autenticacion | **HTTP** | Sin respuesta inmediata, el usuario no puede avanzar |
| Crear una orden (validar stock, descontar, notificar) | **RabbitMQ** | Son multiples pasos que pueden fallar. No necesito la confirmacion al instante |
| Enviar un correo de confirmacion | **RabbitMQ** | El correo puede llegar en 2 segundos o en 10. No es critico |
| Generar un reporte pesado | **RabbitMQ** | Puede tardar minutos. No tiene sentido bloquear al usuario |

- **HTTP gana en el reino de la inmediatez:** Cuando el usuario necesita una respuesta para continuar su flujo.
- **RabbitMQ gana en el reino de la estabilidad:** Cuando necesitas garantizar que algo se procese correctamente, aunque tarde un poco mas.

En nuestro e-commerce, la arquitectura es hibrida: el API Gateway recibe peticiones HTTP del frontend, los GETs se resuelven sincronamente, pero el POST de crear orden **publica un evento** y el procesamiento continua de forma asincrona.

**RabbitMQ no es solo para microservicios.** Se usa tambien en monolitos que necesitan procesamiento asincrono (envio de emails, generacion de PDFs, procesamiento de imagenes, colas de tareas).

### 1.11 Diseno de Eventos — El contrato completo

Antes de implementar el flujo asincrono, hay una decision de diseno critica: **que informacion lleva el evento?**

#### Evento incompleto vs Evento completo

```
Evento incompleto (solo ID):          Evento completo (autosuficiente):
{                                      {
  "orderNumber": "ORD-123"               "orderNumber": "ORD-123",
}                                        "email": "user@email.com",
                                         "items": [
Problema: Inventory tiene que              {"sku": "iphone", "qty": 2}
llamar de vuelta a Order-Service         ]
para obtener los items.                }
Esto RE-ACOPLA los servicios.
                                       Inventory tiene TODO lo que necesita
                                       para trabajar solo. Cero dependencias.
```

**Principio:** Un evento debe ser **autosuficiente**. El consumidor no deberia necesitar llamar a otro servicio para poder procesar el evento. Si lo necesita, estas reintroduciendo acoplamiento sincronico por la puerta trasera.

#### Nested Records — Cohesion y Atomicidad

Para modelar eventos complejos, se usan **records anidados** (Java records). El `OrderItemEvent` existe dentro de `OrderPlacedEvent` porque no tiene sentido por si solo:

```java
public record OrderPlacedEvent(
    String orderNumber,
    String email,
    List<OrderItemEvent> items
) {
    // Record anidado — no existe fuera de este contexto
    public record OrderItemEvent(
        String sku,
        String price,
        Integer quantity
    ) {}
}
```

| Principio | Que logra |
|---|---|
| **Alta Cohesion** | El item de evento no existe por si solo. Depende totalmente del evento padre. Anidarlo refleja esta relacion |
| **Atomicidad** | Al tener todo en un solo archivo, la definicion del esquema es indivisible. Quien tenga el evento, tiene la estructura completa |

#### Distribucion del contrato: Duplicacion estrategica

Cada servicio mantiene su **propia copia** del record de evento en su paquete `event`. No hay un modulo compartido entre servicios.

```
order-service/event/OrderPlacedEvent.java          (publica)
inventory-service/event/OrderPlacedEvent.java       (consume)
notification-service/event/OrderConfirmedEvent.java (consume con mapeo)
```

**Por que duplicar en lugar de compartir?** Un modulo compartido crea una dependencia de compilacion entre servicios. Si cambias el evento en el modulo, todos los servicios que lo usan deben recompilarse y redesplyearse. La duplicacion estrategica mantiene la independencia de deploy.

### 1.12 Patron Saga — Teoria

#### La analogia de las vacaciones

Imagina que organizas unas vacaciones y necesitas reservar tres cosas:

1. Un **vuelo**
2. Un **hotel**
3. Un **alquiler de auto**

**En un monolito:** Todo se hacia en una sola transaccion de base de datos. Si fallaba el auto, la BD hacia `ROLLBACK` automatico y borraba el vuelo y el hotel magicamente. **Todo o nada.**

**En microservicios:** Cada reserva es un servicio independiente con su propia BD. Si reservaste el vuelo y el hotel, pero el auto falla... no hay rollback magico. El vuelo y el hotel ya estan confirmados en bases de datos diferentes.

> **Saga = "Si rompes algo, tienes que arreglarlo tu mismo."**

La solucion: si el auto falla, tu sistema tiene que **cancelar el hotel** y **cancelar el vuelo** de forma explicita. Esas cancelaciones son las **transacciones de compensacion**.

#### Definicion formal

Una **Saga** es una secuencia de transacciones locales distribuidas entre multiples servicios. Cada transaccion local actualiza su propia base de datos y publica un evento que dispara la siguiente transaccion. Si una falla, se ejecutan transacciones de compensacion para deshacer los pasos anteriores.

**El problema que resuelve:** En microservicios no existe una transaccion distribuida global (no hay un `@Transactional` que abarque Order + Inventory + Notification). Cada servicio tiene su propia BD. La Saga coordina la consistencia entre todos.

#### Saga Orquestada vs Saga Coreografiada

| Tipo | Analogia | Como funciona | Ventaja | Desventaja |
|---|---|---|---|---|
| **Orquestada** | Un director de orquesta coordina a los musicos | Un servicio central dirige la secuencia. Le dice a cada servicio que hacer y espera su respuesta | Flujo centralizado y claro, facil de debuggear | El orquestador es un punto unico de fallo y acoplamiento |
| **Coreografiada** | Un baile donde nadie dirige — cada bailarin conoce su paso y reacciona a la musica | No hay director. Cada servicio sabe que evento escuchar y que evento publicar como respuesta | Desacoplamiento total, cada servicio es autonomo | El flujo es implicito, mas dificil de trazar |

**En esta seccion usamos Saga Coreografiada con RabbitMQ.** Cada servicio reacciona a eventos y publica sus propios eventos sin que nadie le diga que hacer.

#### Compensacion — El flujo de fallo

Si un paso de la saga falla, no se puede hacer `rollback` global. En su lugar, se publican **eventos de compensacion** que revierten los pasos anteriores:

```
Flujo normal (Happy Path):
  1. OrderService crea orden          → publica "Orden Creada"
  2. InventoryService valida y descuenta → publica "Stock Reservado"
  3. OrderService actualiza a CONFIRMED
  4. NotificationService envia email de confirmacion

Flujo con fallo (Compensacion):
  1. OrderService crea orden            → publica "Orden Creada"
  2. InventoryService detecta sin stock → publica "Fallo de Inventario"
  3. OrderService actualiza a CANCELLED   ← compensacion
  4. NotificationService envia email      ← "Lo sentimos, tu pedido fue cancelado"
     de disculpa con el motivo
```

**El resultado:** el sistema vuelve a un estado consistente. El cliente recibe una notificacion clara del problema. La consistencia eventual se logra porque cada servicio reacciona a los eventos de fallo y deshace o ajusta sus propios datos.

La compensacion es la alternativa al rollback en un mundo sin transacciones distribuidas.

---

## 2. Infraestructura y Configuracion

### 2.1 RabbitMQ en Docker Compose

```yaml
rabbitmq:
  image: rabbitmq:4.2-management
  container_name: rabbitmq
  ports:
    - "5672:5672"     # AMQP — puerto de comunicacion entre microservicios
    - "15672:15672"   # Management UI — consola web de gestion
  environment:
    RABBITMQ_DEFAULT_USER: guest
    RABBITMQ_DEFAULT_PASS: guest
  volumes:
    - rabbitmq_data:/var/lib/rabbitmq   # Persistencia de colas, exchanges y mensajes
```

- **Puerto 5672**: Protocolo AMQP. Es el canal por donde los microservicios publican y consumen mensajes.
- **Puerto 15672**: UI de gestion (`http://localhost:15672`). Permite ver colas, exchanges, bindings, mensajes encolados, y mover mensajes manualmente.
- **`rabbitmq:4.2-management`**: La imagen `management` incluye el plugin de la consola web. Sin el sufijo, solo tienes el broker sin interfaz.
- **Volumen `rabbitmq_data`**: Sin este volumen, al hacer `docker-compose down` se pierden todas las colas, exchanges y mensajes pendientes.

### 2.2 Dependencia AMQP en los microservicios

Todos los servicios que participan en la mensajeria necesitan la dependencia:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

Esta dependencia trae: el cliente RabbitMQ para Java, `RabbitTemplate` (para publicar), `@RabbitListener` (para consumir), y la autoconfig de Spring que conecta al broker.

### 2.3 MessageConverter — Serializacion JSON

Por defecto, Spring AMQP serializa los mensajes con Java Serialization (bytes ilegibles). Se configura Jackson para que los mensajes viajen como JSON legible:

```java
@Configuration
public class RabbitMQConfig {

    @Bean
    public MessageConverter messageConverter(){
        return new JacksonJsonMessageConverter();
    }
}
```

**Antes (Java Serialization):** El mensaje en RabbitMQ se ve como bytes incomprensibles. Imposible de depurar desde la consola.

**Despues (JSON):** El mensaje se ve como `{"orderNumber":"abc-123","email":"user@mail.com","items":[...]}`. Legible, debuggeable, y compatible con cualquier lenguaje.

Esta configuracion se repite en **cada servicio** que produce o consume mensajes (Order, Inventory, Notification).

---

## 3. Flujo Principal de Negocio (Happy Path)

### Concepto

El flujo completo sigue el patron: **Publicar → Validar → Confirmar → Notificar → Sincronizar**.

```
POST /api/v1/orders
       │
       ▼
┌──────────────┐   order.placed   ┌──────────────────┐   order.confirmed   ┌──────────────────────┐
│ Order Service │ ──────────────> │ Inventory Service │ ──────────────────> │ Notification Service  │
│ (Producer)    │                 │ (Validador/Saga)  │                     │ (Envio de correos)    │
│ status=PLACED │                 │ reduce stock      │                     │ Email de confirmacion │
└──────┬───────┘                 └──────────────────┘                     └───────────────────────┘
       │                                  │
       │         order.confirmed          │
       │ <────────────────────────────────┘
       ▼
 status=CONFIRMED
```

### 3.1 Definicion del evento: `OrderPlacedEvent`

El evento es el **contrato** entre los servicios. Se usa un `record` de Java que es inmutable por naturaleza:

```java
public record OrderPlacedEvent(
        String orderNumber,
        String email,
        List<OrderItemEvent> items
) {
    public record OrderItemEvent(
            String sku,
            String price,
            Integer quantity){
    }
}
```

- **`orderNumber`**: Identificador unico de la orden (UUID generado en Order Service).
- **`email`**: Necesario para que Notification Service sepa a quien notificar sin tener que consultar a Order Service.
- **`items`**: Lista de productos con SKU, precio y cantidad. Inventory necesita estos datos para validar y descontar stock.

Cada servicio define su **propia copia** del evento (mismo paquete `event`, misma estructura). No hay un modulo compartido — esto mantiene la independencia entre servicios.

### 3.2 Producer en Order Service

El servicio de ordenes ya no llama a Inventory por HTTP. Ahora guarda la orden con estado `PLACED` y publica un evento:

```java
@Override
@Transactional
public OrderResponse placeOrder(OrderRequest orderRequest, String userId) {
    // ...
    order.setOrderNumber(UUID.randomUUID().toString());
    order.setStatus(OrderStatus.PLACED);
    Order savedOrder = orderRepository.save(order);

    // Construir el evento con los items de la orden
    List<OrderPlacedEvent.OrderItemEvent> orderItems = savedOrder.getOrderLineItemsList().stream()
            .map(item -> new OrderPlacedEvent.OrderItemEvent(
                    item.getSku(), item.getPrice().toString(), item.getQuantity()
            )).toList();

    OrderPlacedEvent event = new OrderPlacedEvent(
            savedOrder.getOrderNumber(), orderRequest.getEmail(), orderItems
    );

    // Publicar al exchange con routing key "order.placed"
    rabbitTemplate.convertAndSend("order-events", "order.placed", event);

    return orderMapper.toOrderResponse(savedOrder);
}
```

**Cambios clave respecto a la version sincrona:**
- Ya no se inyecta `InventoryClient` — no hay llamada HTTP directa
- Se inyecta `RabbitTemplate` — el mecanismo de publicacion
- La orden se guarda inmediatamente con `OrderStatus.PLACED` (estado intermedio, no final)
- `@CircuitBreaker` y `@Retry` se eliminan del metodo porque ya no hay comunicacion sincrona que proteger

### 3.3 Topologia RabbitMQ: Exchange, Queue y Binding

#### Order Service (Publisher)

```java
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "order-events";

    @Bean
    public TopicExchange orderEventsExchange(){
        return new TopicExchange(EXCHANGE_NAME);
    }

    // Colas donde Order escucha las respuestas de la saga
    @Bean
    public Queue orderConfirmedQueue(){
        return new Queue("order-confirmed-queue", true);  // durable = true
    }

    @Bean
    public Binding confirmedBinding(Queue orderConfirmedQueue, TopicExchange orderEventsExchange){
        return BindingBuilder.bind(orderConfirmedQueue)
                .to(orderEventsExchange)
                .with("order.confirmed");
    }

    @Bean
    public Queue orderCancelledQueue(){
        return new Queue("order-cancelled-queue", true);
    }

    @Bean
    public Binding cancelledBinding(Queue orderCancelledQueue, TopicExchange orderEventsExchange){
        return BindingBuilder.bind(orderCancelledQueue)
                .to(orderEventsExchange)
                .with("order.cancelled");
    }
}
```

#### Inventory Service (Consumer del evento inicial)

```java
@Bean
public Queue inventoryQueue(){
    return new Queue("inventory-queue", true);
}

@Bean
public TopicExchange orderEventsExchange(){
    return new TopicExchange("order-events");
}

@Bean
public Binding binding(Queue inventoryQueue, TopicExchange orderEventsExchange){
    return BindingBuilder.bind(inventoryQueue)
            .to(orderEventsExchange)
            .with("order.placed");
}
```

### Topologia completa del exchange `order-events` (TopicExchange)

```
                        order-events (TopicExchange)
                       ┌────────────────────────────────┐
                       │                                │
  order.placed ───────>│──> inventory-queue              │
                       │                                │
  order.confirmed ────>│──> order-confirmed-queue        │
                       │──> notification-queue           │
                       │                                │
  order.cancelled ────>│──> order-cancelled-queue        │
                       │──> notification-queue           │
                       └────────────────────────────────┘
```

Se usa un **TopicExchange** porque las routing keys (`order.placed`, `order.confirmed`, `order.cancelled`) permiten enrutamiento selectivo. Cada cola se suscribe solo a las keys que le interesan.

### 3.4 Consumer en Inventory Service

Inventory Service escucha `OrderPlacedEvent`, valida stock, descuenta, y responde con un evento de confirmacion o cancelacion:

```java
@RequiredArgsConstructor
@Component
@Slf4j
public class OrderEventsListener {
    private final InventoryService inventoryService;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = "inventory-queue")
    public void handleOrderPlacedEvent(OrderPlacedEvent event) {
        log.info("Evento recibido en Inventario para Orden: {}", event.orderNumber());

        try {
            // Validar que TODOS los productos tengan stock suficiente
            boolean allProductsInStock = event.items().stream()
                    .allMatch(item -> inventoryService.isInStock(item.sku(), item.quantity()));

            if (!allProductsInStock) {
                cancelOrder(event, "Stock insuficiente en uno o mas productos");
                return;
            }

            // Descontar stock de cada producto
            event.items().forEach(item -> {
                inventoryService.reduceStock(item.sku(), item.quantity());
            });

            // Publicar evento de confirmacion
            rabbitTemplate.convertAndSend("order-events", "order.confirmed", event);
            log.info("Stock descontado para Orden numero: {}", event.orderNumber());

        } catch (Exception e) {
            log.error("Error inesperado: {}", e.getMessage());
            cancelOrder(event, "Error tecnico en el procesamiento de inventario");
        }
    }

    private void cancelOrder(OrderPlacedEvent event, String reason) {
        OrderCancelledEvent cancelledEvent = new OrderCancelledEvent(
                event.orderNumber(), event.email(), reason
        );
        rabbitTemplate.convertAndSend("order-events", "order.cancelled", cancelledEvent);
    }
}
```

**Inventory actua como validador de la saga.** Su logica:
1. Primero verifica stock con `isInStock()` — sin descontar nada
2. Si algun producto no tiene stock suficiente → publica `OrderCancelledEvent` y sale
3. Si todo tiene stock → descuenta con `reduceStock()` y publica el evento original como confirmacion
4. Si ocurre un error inesperado → publica cancelacion con razon tecnica

---

## 4. Estrategia Fan-Out y Notification Service

### Concepto

Fan-Out significa que un mismo evento llega a **multiples consumidores**. El evento `order.confirmed` que publica Inventory es consumido simultaneamente por:

1. **Order Service** → Para actualizar el status de `PLACED` a `CONFIRMED`
2. **Notification Service** → Para enviar el correo de confirmacion al usuario

Esto se logra porque ambos servicios tienen colas diferentes (`order-confirmed-queue` y `notification-queue`) bindeadas a la misma routing key `order.confirmed` en el mismo exchange.

### 4.1 Configuracion RabbitMQ en Notification Service

Notification escucha tanto confirmaciones como cancelaciones:

```java
@Bean
public Queue notificationQueue(){
    return QueueBuilder.durable("notification-queue")
            .withArgument("x-dead-letter-exchange", "notification-dlx")
            .withArgument("x-dead-letter-routing-key", "notification.dead")
            .build();
}

@Bean
public Binding binding(Queue notificationQueue, TopicExchange orderEventsExchange){
    return BindingBuilder.bind(notificationQueue)
            .to(orderEventsExchange)
            .with("order.confirmed");
}

@Bean
public Binding cancelledBinding(Queue notificationQueue, TopicExchange orderEventsExchange) {
    return BindingBuilder.bind(notificationQueue)
            .to(orderEventsExchange)
            .with("order.cancelled");
}
```

La cola tiene dos bindings: uno para `order.confirmed` y otro para `order.cancelled`. Ambos tipos de evento llegan a la misma cola y se despachan con `@RabbitHandler`.

### 4.2 Mapeo de clases entre servicios (DefaultClassMapper)

Cuando Inventory publica un `OrderPlacedEvent` como confirmacion, el JSON incluye un header `__TypeId__` con el nombre de clase del emisor (`com.ecommerce.inventory_service.event.OrderPlacedEvent`). Notification Service no tiene esa clase — tiene su propia `OrderConfirmedEvent`.

La solucion es un `DefaultClassMapper` que traduce las identidades:

```java
@Bean
public MessageConverter messageConverter() {
    JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();
    DefaultClassMapper classMapper = new DefaultClassMapper();
    classMapper.setTrustedPackages("*");

    Map<String, Class<?>> idClassMapping = new HashMap<>();
    // "Lo que viene del emisor" -> "Clase local que lo recibe"
    idClassMapping.put("com.ecommerce.inventory_service.event.OrderPlacedEvent",
                       OrderConfirmedEvent.class);
    idClassMapping.put("com.ecommerce.inventory_service.event.OrderCancelledEvent",
                       OrderCancelledEvent.class);

    classMapper.setIdClassMapping(idClassMapping);
    converter.setClassMapper(classMapper);
    return converter;
}
```

Sin este mapeo, RabbitMQ intenta deserializar con la clase del emisor y lanza `ClassNotFoundException`.

### 4.3 Listener con @RabbitHandler (Consumo Competitivo)

En lugar de un `@RabbitListener` por metodo, se usa `@RabbitListener` a nivel de clase y `@RabbitHandler` por metodo. Esto permite que **una sola cola** despache a metodos diferentes segun el tipo de mensaje:

```java
@RequiredArgsConstructor
@Component
@Slf4j
@RabbitListener(queues = "notification-queue")   // La clase escucha la cola
public class OrderEventsListener {

    private final JavaMailSender mailSender;

    @RabbitHandler   // Despacha segun el tipo del payload
    public void handleOrderConfirmedEvent(OrderConfirmedEvent event) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(event.email());
        message.setSubject("Orden Confirmada - " + event.orderNumber());
        message.setText("Hola!\n\n" +
                "Tu pedido con numero " + event.orderNumber() +
                " ha sido recibido exitosamente.\n" +
                "Pronto recibiras mas noticias sobre el envio.\n\n" +
                "Gracias por comprar con nosotros!");
        mailSender.send(message);
    }

    @RabbitHandler
    public void handleOrderCancelledEvent(OrderCancelledEvent event) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(event.email());
        message.setSubject("Actualizacion de tu pedido - " + event.orderNumber());
        message.setText("Lamentamos informarte que tu pedido ha sido cancelado.\n\n" +
                "Motivo: " + event.reason() + ".\n" +
                "Si se realizo algun cargo, sera reembolsado a la brevedad.");
        mailSender.send(message);
    }
}
```

### 4.4 Integracion con Mailtrap (Desarrollo)

Para pruebas en desarrollo se usa **Mailtrap**, un servicio que captura correos sin enviarlos al destinatario real. La configuracion se coloca en el perfil por defecto:

```yaml
spring:
  mail:
    host: sandbox.smtp.mailtrap.io
    port: 2525
    username: <tu-username-mailtrap>
    password: <tu-password-mailtrap>
```

Todos los correos enviados con `JavaMailSender` llegan a la bandeja de Mailtrap, donde se pueden inspeccionar sin riesgo de spam.

### 4.5 Paso a Produccion: Gmail/SMTP y Perfiles de Spring

Para produccion se configura un perfil `prod` que usa Gmail como SMTP real:

```yaml
# application-prod.yaml
spring:
  mail:
    host: smtp.gmail.com
    port: 587
    username: tu-correo@gmail.com
    password: <app-password-de-gmail>    # NO la contrasena de tu cuenta
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true
```

- **App Password**: Gmail no permite autenticacion con contrasena normal si tienes 2FA activado. Se genera un "App Password" desde la configuracion de seguridad de Google.
- **Activacion del perfil**: Se arranca el servicio con `--spring.profiles.active=prod` o con la variable de entorno `SPRING_PROFILES_ACTIVE=prod`.
- **Config Server**: La config de mail puede vivir en el repositorio de config centralizado (`notification-service-prod.yml`), siguiendo el patron de la Seccion 5.

---

## 5. Patron Saga: Validacion y Compensacion

### Concepto

Una Saga es una secuencia de transacciones locales donde cada paso publica un evento que dispara el siguiente. Si un paso falla, se publican **eventos de compensacion** que deshacen los pasos anteriores.

En esta implementacion se usa una **Saga coreografiada** (sin orquestador central). Cada servicio sabe que hacer al recibir un evento y que publicar como respuesta.

### 5.1 Contratos de la Saga

Tres records definen el contrato de la saga:

```java
// Evento inicial — Order Service publica al crear una orden
public record OrderPlacedEvent(
        String orderNumber, String email,
        List<OrderItemEvent> items) { }

// Evento de exito — Inventory publica despues de validar y descontar stock
public record OrderConfirmedEvent(
        String orderNumber, String email) { }

// Evento de compensacion — Inventory publica si no hay stock o hay error
public record OrderCancelledEvent(
        String orderNumber, String email,
        String reason) { }
```

### 5.2 La maquina de estados de la Saga

```
          placeOrder()              Inventory valida
              │                          │
              ▼                          ▼
┌─────────────────────┐    ┌──────────────────────────────┐
│   PLACED            │    │  stock suficiente?           │
│   (estado inicial)  │    │  SI → reduceStock() + emit   │
│                     │    │       order.confirmed        │
│                     │    │  NO → emit order.cancelled   │
└─────────┬───────────┘    └──────────────┬───────────────┘
          │                               │
          │     order.confirmed           │     order.cancelled
          │◄──────────────────────────────│────────────────────►│
          ▼                                                     ▼
┌─────────────────────┐                            ┌────────────────────┐
│   CONFIRMED         │                            │   CANCELLED        │
│   (estado final OK) │                            │   (compensacion)   │
└─────────────────────┘                            └────────────────────┘
```

### 5.3 Inventory como Validador de Reglas de Negocio

Inventory no solo descuenta stock — **primero valida**. El patron es:

1. **Validar todo antes de modificar nada** (`isInStock()` para cada item)
2. Si alguno falla → cancelar sin haber tocado la BD
3. Si todo pasa → descontar stock → confirmar

Esto evita el escenario de "desconte parcialmente y ahora tengo que devolver". La validacion completa se hace **antes** de la primera escritura.

### 5.4 Notificaciones Basadas en Certezas

Notification Service **solo reacciona a estados finales**. No escucha `order.placed` (que es un estado intermedio e incierto). Solo escucha:

- `order.confirmed` → Correo de confirmacion
- `order.cancelled` → Correo de disculpa con motivo

**Principio:** No envies un correo que despues tengas que contradecir. No le digas al usuario "Tu orden fue recibida" si todavia no sabes si hay stock.

### 5.5 Consistencia Eventual en Order Service

Order Service escucha las respuestas de la saga para actualizar el estado final de la orden:

```java
@RequiredArgsConstructor
@Component
@Slf4j
public class OrderEventsListener {
    private final OrderService orderService;

    @RabbitListener(queues = "order-confirmed-queue")
    public void handleOrderConfirmed(OrderPlacedEvent event){
        orderService.updateOrderStatus(event.orderNumber(), OrderStatus.CONFIRMED);
    }

    @RabbitListener(queues = "order-cancelled-queue")
    public void handleOrderCancelled(OrderPlacedEvent event) {
        orderService.updateOrderStatus(event.orderNumber(), OrderStatus.CANCELLED);
    }
}
```

El metodo `updateOrderStatus` busca la orden por `orderNumber` y actualiza el campo `status`:

```java
@Override
@Transactional
public void updateOrderStatus(String orderNumber, OrderStatus newStatus) {
    orderRepository.findByOrderNumber(orderNumber).ifPresentOrElse(
            order -> {
                order.setStatus(newStatus);
                orderRepository.save(order);
            },
            () -> log.error("No se encontro la orden {} para actualizar", orderNumber)
    );
}
```

Esto requirio agregar `findByOrderNumber` al repositorio:

```java
public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByUserId(String userId);
    Optional<Order> findByOrderNumber(String orderNumber);
}
```

Y el enum `OrderStatus` en el modelo:

```java
public enum OrderStatus {
    PLACED,       // Orden creada, pendiente de validacion
    CONFIRMED,    // Inventario valido y desconto stock
    CANCELLED     // Inventario rechazo (sin stock o error)
}
```

En la entidad `Order`, el status se persiste como String:

```java
@Enumerated(EnumType.STRING)
private OrderStatus status;
```

El `OrderResponse` tambien incluye el status para que el cliente pueda consultar el estado de su orden.

---

## 6. Robustez Tecnica y Resiliencia

### 6.1 Cierre de la Saga y Sincronizacion de Estados

El flujo completo con todos los participantes y sus estados:

```
┌───────────────┐         ┌──────────────────┐         ┌────────────────────┐
│ Order Service  │         │ Inventory Service │         │ Notification       │
│                │         │                  │         │ Service            │
│ 1. PLACED      │─event──>│ 2. Validar stock │         │                    │
│                │         │    Descontar     │─event──>│ 4. Enviar correo   │
│ 3. CONFIRMED   │<─event──│    Confirmar     │         │    de confirmacion │
│    o CANCELLED │         │    o Cancelar    │         │    o cancelacion   │
└───────────────┘         └──────────────────┘         └────────────────────┘
```

La saga termina cuando Order Service recibe `order.confirmed` o `order.cancelled` y actualiza su BD. Los tres servicios quedan en un estado consistente **eventualmente** — no al mismo tiempo, pero si de forma garantizada mientras los mensajes se entreguen.

### 6.2 Retry Pattern y Reintentos Automaticos

Spring AMQP reintenta automaticamente los mensajes que fallan (cuando el listener lanza una excepcion). La configuracion por defecto de Spring Retry:

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        retry:
          enabled: true
          initial-interval: 1000    # 1 segundo entre primer y segundo intento
          max-attempts: 3           # Maximo 3 intentos
          multiplier: 2.0           # Backoff exponencial: 1s -> 2s -> 4s
```

Notification Service incluye `spring-retry` como dependencia:

```xml
<dependency>
    <groupId>org.springframework.retry</groupId>
    <artifactId>spring-retry</artifactId>
</dependency>
```

Si el listener lanza excepcion (por ejemplo, el servidor SMTP no responde), Spring reintenta automaticamente. Despues de agotar los intentos, el mensaje va a la **Dead Letter Queue**.

### 6.3 Dead Letter Queues y Gestion de Mensajes Fallidos

Cuando un mensaje agota todos los reintentos, se envia a una Dead Letter Queue (DLQ) en lugar de perderse. La configuracion en Notification Service:

```java
// Cola principal con DLQ configurada
@Bean
public Queue notificationQueue(){
    return QueueBuilder.durable("notification-queue")
            .withArgument("x-dead-letter-exchange", "notification-dlx")
            .withArgument("x-dead-letter-routing-key", "notification.dead")
            .build();
}

// Exchange dedicado para mensajes muertos
@Bean
public DirectExchange deadLetterExchange(){
    return new DirectExchange("notification-dlx");
}

// Cola donde se acumulan los mensajes fallidos
@Bean
public Queue deadLetterQueue(){
    return new Queue("notification-dlq", true);
}

// Binding entre el exchange y la cola de dead letters
@Bean
public Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
    return BindingBuilder.bind(deadLetterQueue)
            .to(deadLetterExchange)
            .with("notification.dead");
}
```

**Flujo de un mensaje fallido:**

```
notification-queue ──(3 intentos fallidos)──> notification-dlx ──> notification-dlq
                                              (DirectExchange)     (mensaje almacenado)
```

Los mensajes en la DLQ se pueden:
- **Inspeccionar** desde la consola de RabbitMQ (ver payload, headers, razon de fallo)
- **Reintentar manualmente** con el plugin Shovel (moverlos de vuelta a la cola original)
- **Analizar** para detectar patrones de error recurrentes

### 6.4 Observabilidad: Headers x-death

Cuando un mensaje llega a la DLQ, RabbitMQ le agrega automaticamente headers `x-death` con informacion del fallo:

| Header | Que contiene |
|---|---|
| `x-death[0].queue` | Nombre de la cola original (`notification-queue`) |
| `x-death[0].reason` | Motivo del rechazo (`rejected`, `expired`, etc.) |
| `x-death[0].count` | Cuantas veces ha sido rechazado |
| `x-death[0].exchange` | Exchange original que lo enruto |
| `x-death[0].routing-keys` | Routing keys originales del mensaje |
| `x-death[0].time` | Timestamp del rechazo |

Estos headers son visibles desde la UI de RabbitMQ al inspeccionar un mensaje en la DLQ. Permiten diagnosticar **por que fallo** y **cuantas veces se intento** sin necesidad de revisar logs del servicio.

---

## 7. Notas de Implementacion

### 7.1 Persistencia: Colas y Exchanges como `durable`

Todas las colas se declaran con `durable = true` (segundo parametro del constructor `new Queue("nombre", true)` o con `QueueBuilder.durable()`). Esto significa que sobreviven reinicios de RabbitMQ.

Los `TopicExchange` y `DirectExchange` tambien son `durable` por defecto en sus constructores.

Sin durabilidad, un reinicio del contenedor Docker borra las colas y todos los mensajes pendientes se pierden.

### 7.2 Error PRECONDITION_FAILED e Inmutabilidad

Si cambias la configuracion de una cola existente (por ejemplo, agregarle argumentos de DLQ a una cola que ya fue creada sin ellos), RabbitMQ lanza:

```
PRECONDITION_FAILED - inequivalent arg 'x-dead-letter-exchange'
```

**RabbitMQ no permite modificar colas existentes.** Las colas son inmutables una vez creadas. La solucion:

1. **Eliminar la cola existente** desde la UI de RabbitMQ (`http://localhost:15672` → Queues → Delete)
2. **Reiniciar el servicio** para que Spring la recree con la nueva configuracion
3. **Alternativa Docker:** `docker-compose down -v` para borrar volumenes y empezar limpio (util en desarrollo)

Esto aplica tambien a cambios en bindings o argumentos del exchange.

### 7.3 Plugin Shovel: Recuperacion Manual de Mensajes

El plugin **Shovel** permite mover mensajes de una cola a otra desde la UI de RabbitMQ. El caso de uso principal: **recuperar mensajes de la DLQ y reenviarlos a la cola original** para reprocesarlos.

Para activar el plugin:

```bash
docker exec -it rabbitmq rabbitmq-plugins enable rabbitmq_shovel rabbitmq_shovel_management
```

Una vez activado, en la UI de RabbitMQ aparece la seccion "Shovel Management" donde se puede configurar:
- **Source**: `notification-dlq` (cola de mensajes fallidos)
- **Destination**: `notification-queue` (cola original para reprocesar)
- **Ack Mode**: `on-confirm` (borra de la DLQ solo despues de confirmar que llego a la cola destino)

Esto permite recuperar mensajes fallidos sin escribir codigo — directamente desde la interfaz de administracion.

---

## Archivos clave de esta seccion

| Archivo | Que hace |
|---|---|
| `docker-compose.yml` | Agrega servicio RabbitMQ con puertos AMQP (5672) y Management UI (15672), mas volumen persistente |
| `order-service/.../config/RabbitMQConfig.java` | Declara exchange `order-events`, colas de respuesta (`order-confirmed-queue`, `order-cancelled-queue`) y MessageConverter JSON |
| `order-service/.../event/OrderPlacedEvent.java` | Record con orderNumber, email e items — contrato del evento inicial |
| `order-service/.../event/OrderConfirmedEvent.java` | Record de confirmacion (orderNumber, email) |
| `order-service/.../event/OrderCancelledEvent.java` | Record de cancelacion (orderNumber, email, reason) |
| `order-service/.../listener/OrderEventsListener.java` | Consume `order.confirmed` y `order.cancelled` para actualizar el status de la orden (consistencia eventual) |
| `order-service/.../service/impl/OrderServiceImpl.java` | Publica `OrderPlacedEvent` via RabbitTemplate en lugar de llamar a Inventory por HTTP |
| `order-service/.../model/OrderStatus.java` | Enum `PLACED`, `CONFIRMED`, `CANCELLED` |
| `order-service/.../model/Order.java` | Agrega campo `status` con `@Enumerated(EnumType.STRING)` |
| `inventory-service/.../config/RabbitMQConfig.java` | Declara `inventory-queue` bindeada a `order.placed` |
| `inventory-service/.../listener/OrderEventsListener.java` | Consume `OrderPlacedEvent`, valida stock, descuenta, y publica confirmacion o cancelacion |
| `notification-service/.../config/RabbitMQConfig.java` | Declara `notification-queue` con DLQ, bindings a `order.confirmed` y `order.cancelled`, y ClassMapper para traducir tipos entre servicios |
| `notification-service/.../listener/OrderEventsListener.java` | Envia correos de confirmacion o cancelacion via `JavaMailSender` usando `@RabbitHandler` |
| `notification-service/pom.xml` | Dependencias: `spring-boot-starter-amqp`, `spring-boot-starter-mail`, `spring-retry` |
