# Seccion 8: Resiliencia y Limites de la Sincronia en Arquitecturas Distribuidas

> Notas del curso de Microservicios — Resilience4j en Spring Boot 4

---

## Objetivo de la seccion

Pasar de un sistema que **asume que todo funciona** (Happy Path) a uno que **anticipa y maneja fallos** de manera automatica. Se implementan tres patrones de resiliencia con Resilience4j:

- **Circuit Breaker:** Corta la comunicacion con un servicio caido para evitar fallos en cascada
- **Retry Pattern:** Reintenta automaticamente ante errores transitorios con Exponential Backoff
- **TimeLimiter (experimento):** Intenta controlar la latencia, pero revela un **limite duro** de la sincronia HTTP

Descubrimiento critico: la arquitectura bloqueante (Spring MVC) tiene un limite fundamental para la consistencia transaccional cuando se intenta controlar tiempos de ejecucion.

---

## 1. El Problema: Fallo en Cascada

### Concepto

Hasta ahora, el sistema asumia tres cosas que en produccion son mentira:

| Asuncion | Realidad |
|---|---|
| Inventory siempre responde | Puede caerse, reiniciarse, o saturarse |
| La BD nunca falla | Puede bloquearse, tener latencia, o caer |
| La red es rapida | Hay parpadeos, congestion, paquetes perdidos |

### El escenario critico

1. Un usuario confirma un pedido en `Order-Service`
2. `Order-Service` llama a `Inventory-Service` para reducir stock
3. `Inventory-Service` **no responde** (o tarda 30 segundos)
4. El hilo que atendia esa peticion se queda **bloqueado esperando**
5. Entran 1000 usuarios mas → 1000 hilos bloqueados
6. `Order-Service` se queda sin recursos → **cae**
7. Otros servicios que dependian de `Order-Service` tambien caen → **efecto domino**

```
Estado normal:
Usuario → Order-Service → Inventory-Service (respondiendo)

Estado de fallo (sin proteccion):
Usuario (1000 esperando) → Order-Service (hilos bloqueados) → Inventory-Service (sin respuesta)
```

**Un solo servicio roto puede tumbar toda la plataforma.** La solucion: patrones de resiliencia.

---

## 2. Circuit Breaker — Proteccion contra fallos totales

### Concepto

El Circuit Breaker es un mecanismo que **detecta fallos repetidos** en un servicio y **corta automaticamente** la comunicacion antes de que el fallo se propague. El nombre viene de los disyuntores electricos: cuando hay sobrecarga, se corta el circuito para proteger la instalacion.

### Los 3 estados (Maquina de Estados)

```
                errores > 50%              timeout (5s)
  CLOSED ──────────────────────> OPEN ─────────────────────> HALF-OPEN
    (Normal)                    (Cortado)                    (Probando)
      ^                                                         │
      │              peticiones de prueba exitosas               │
      └─────────────────────────────────────────────────────────┘
                     peticiones de prueba fallan → vuelve a OPEN
```

| Estado | Comportamiento |
|---|---|
| **CLOSED** (Normal) | Las peticiones pasan normalmente. El sistema **cuenta los errores**. Cuando superan el umbral → se abre |
| **OPEN** (Cortado) | Ninguna peticion llega a Inventory. El CB la detiene **inmediatamente** y retorna un fallback sin esperar timeout |
| **HALF-OPEN** (Probando) | Despues del timeout, deja pasar **algunas peticiones de prueba**. Si funcionan → CLOSED. Si fallan → OPEN |

### Implementacion

#### 2.1 Dependencias: `order-service/pom.xml`

```xml
<!-- Resilience4j — libreria estandar de Spring Cloud para resiliencia -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.3.0</version>
</dependency>

<!-- AOP — necesario para que las anotaciones @CircuitBreaker, @Retry funcionen -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
    <version>3.3.0</version>
</dependency>

<!-- Actuator — para exponer el estado del Circuit Breaker en /health -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

- **resilience4j-spring-boot3**: La libreria que proporciona las anotaciones `@CircuitBreaker`, `@Retry`, `@TimeLimiter`.
- **spring-boot-starter-aop**: Sin AOP, las anotaciones de Resilience4j **no hacen nada**. Son proxies que interceptan la llamada al metodo.
- **spring-boot-starter-actuator**: Permite ver el estado del circuit breaker en tiempo real via `/actuator/health`.

#### 2.2 Configuracion YAML: `config-data/order-service.yml`

```yaml
# Exponer el estado del Circuit Breaker via Actuator
management:
  endpoint:
    health:
      show-details: always
  health:
    circuitbreakers:
      enabled: true
  endpoints:
    web:
      exposure:
        include: "refresh, health, circuitbreakers"

resilience4j:
  circuitbreaker:
    instances:
      inventory:                              # Nombre del CB (debe coincidir con @CircuitBreaker(name = "inventory"))
        registerHealthIndicator: true         # Visible en /actuator/health
        slidingWindowType: COUNT_BASED        # Evalua por cantidad de llamadas (no por tiempo)
        slidingWindowSize: 5                  # Ventana de las ultimas 5 llamadas
        failureRateThreshold: 50              # Si 50% de las 5 llamadas fallan → OPEN
        waitDurationInOpenState: 5s           # Tiempo en OPEN antes de pasar a HALF-OPEN
        permittedNumberOfCallsInHalfOpenState: 3  # Cuantas peticiones de prueba deja pasar en HALF-OPEN
```

- **`slidingWindowType: COUNT_BASED`**: Evalua las ultimas N llamadas. La alternativa es `TIME_BASED` (evalua las llamadas en los ultimos N segundos).
- **`slidingWindowSize: 5`**: Con una ventana de 5, si 3 de las ultimas 5 llamadas fallan (>50%), se abre el circuito.
- **`waitDurationInOpenState: 5s`**: En produccion seria mas alto (30s, 60s). En desarrollo usamos 5s para ver las transiciones rapido.
- **`permittedNumberOfCallsInHalfOpenState: 3`**: Al pasar a HALF-OPEN, deja pasar 3 llamadas de prueba. Si la mayoria funcionan → CLOSED.

#### 2.3 El servicio protegido: `OrderServiceImpl.java`

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {
    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final InventoryClient inventoryClient;

    @Value("${order.enabled:false}")
    private boolean ordersEnabled;

    // Metodo fallback — se ejecuta cuando el CB esta OPEN o cuando falla la llamada
    public OrderResponse fallbackMethod(OrderRequest orderRequest, String userId, Throwable throwable){
        log.error("Circuit Breaker activado. Causa: {}", throwable.getMessage());
        throw new RuntimeException("El Servicio de Inventario no responde. Por favor intente mas tarde.");
    }

    @Override
    @Transactional
    @CircuitBreaker(name = "inventory", fallbackMethod = "fallbackMethod")
    @Retry(name = "inventory")
    public OrderResponse placeOrder(OrderRequest orderRequest, String userId) {
        // ... logica de negocio
    }
}
```

**Reglas del fallback:**
- La firma del metodo fallback debe coincidir con el metodo original + un parametro `Throwable` al final
- Si el fallback devuelve un valor, ese valor es lo que recibe el cliente (degradacion graceful)
- En este caso, el fallback lanza excepcion porque no tiene sentido crear una orden sin validar inventario

#### 2.4 El cliente HTTP: `InventoryClient.java`

```java
public interface InventoryClient {

    @PutExchange("/api/v1/inventory/reduce/{sku}")
    String reduceStock(@PathVariable String sku, @RequestParam Integer quantity);
}
```

Se usa la API declarativa de Spring (`@PutExchange`) en lugar de `WebClient` manual. El URL se resuelve via Eureka + Load Balancer.

---

## 3. Retry Pattern — La insistencia elegante

### Concepto

El Retry Pattern asume que ciertas fallas son **temporales (transitorias)**. Si fallo ahora, quizas en 200 milisegundos funcione. Es un patron hibrido:

- **Como Patron de Diseno:** Se parece a un Decorator/Proxy. Envuelves tu llamada en logica que la repite.
- **Como Patron Arquitectonico:** Es vital para la resiliencia. Define como se comportan los servicios cuando la red es hostil.

### Monolito vs Microservicios

| | Monolito | Microservicios |
|---|---|---|
| **Comunicacion** | Todo en una sola memoria | Todo distribuido en red |
| **Tipo de errores** | Deterministas (NullPointer, logica) | Transitorios (red, timeout, congestion) |
| **Retry util?** | Rara vez | Esencial |

Sin Retry, una arquitectura de microservicios es extremadamente fragil. Un solo fallo transitorio de red rompe todo.

### Las 3 Reglas de Oro

| Regla | Pregunta | Ejemplo |
|---|---|---|
| **Count** | Cuantas veces insisto? | 3 veces |
| **Interval** | Cuanto espero entre intentos? | 2 segundos |
| **Backoff** | Como crece el tiempo de espera? | Exponencial |

### Estrategias de Backoff

**Fixed Backoff (Fijo):** Espero siempre el mismo tiempo: `2s → 2s → 2s`
- Problema: Si el servicio esta saturado, seguiras bombardeandolo con la misma frecuencia.

**Exponential Backoff (Exponencial):** El tiempo de espera crece exponencialmente: `2s → 4s → 8s`
- Best Practice recomendada por AWS, Google Cloud y Azure.
- No saturas al servicio que ya esta sufriendo. Le das cada vez mas tiempo para recuperarse.

### Cuando usarlo y cuando NO

| Usalo para (Transitorios) | NO lo uses para (Permanentes) |
|---|---|
| `503 Service Unavailable` | `401 Unauthorized` |
| `Timeout` | `400 Bad Request` |
| `Connection Refused` | `404 Not Found` |
| `Network Error` | `403 Forbidden` |
| `429 Too Many Requests` | `500 Internal Server Error` |

**Clave:** Si te equivocaste en la contrasena, por mas que reintentes 100 veces, seguira estando mal. Solo gastaras recursos.

### Implementacion: `config-data/order-service.yml`

```yaml
resilience4j:
  retry:
    instances:
      inventory:
        maxRetryAttempts: 3                   # Maximo 3 intentos
        waitDuration: 2s                      # Espera inicial de 2 segundos
        enableExponentialBackoff: true        # Activa crecimiento exponencial
        exponentialBackoffMultiplier: 2       # Multiplica x2 cada intento: 2s → 4s → 8s
```

**Secuencia real:** Si `Inventory-Service` falla:
1. Intento 1: Falla → espera 2s
2. Intento 2: Falla → espera 4s
3. Intento 3: Falla → se rinde → el Circuit Breaker cuenta este fallo

### Orden de ejecucion: Retry + Circuit Breaker

Cuando `@Retry` y `@CircuitBreaker` estan juntos en el mismo metodo:

```
Peticion → Retry (intenta N veces) → si todos fallan → Circuit Breaker (cuenta el fallo)
                                                          ↓
                                              Si supera umbral → OPEN → fallback
```

El Retry actua **dentro** del Circuit Breaker. El CB ve el resultado final despues de todos los reintentos.

---

## 4. El Experimento TimeLimiter — El limite de la sincronia

### Concepto

En sistemas distribuidos, **una respuesta lenta es mas peligrosa que una respuesta fallida**. Si un servicio tarda 30 segundos en responder, el hilo del cliente se queda bloqueado consumiendo recursos. Si falla en 10ms, el hilo se libera inmediatamente.

El **TimeLimiter** establece un limite maximo de espera: "Si no terminas en 3 segundos, me voy."

### Thread Pool Exhaustion (Agotamiento de Hilos)

Si el servidor tiene capacidad para 10 clientes simultaneos y los 10 estan esperando una respuesta lenta, el cliente #11 es rechazado aunque el sistema no tenga errores reales. El recurso escaso no es CPU ni memoria, **son los hilos**.

### La implementacion que se probo (y se elimino)

```yaml
# COMENTADO — se elimino del flujo final
# resilience4j:
#   timelimiter:
#     instances:
#       inventory:
#         timeoutDuration: 3s
#         cancelRunningFuture: true
```

```java
// CODIGO QUE SE PROBO (y se elimino)
// @TimeLimiter requiere que el metodo devuelva CompletableFuture
@CircuitBreaker(name = "inventory", fallbackMethod = "fallbackMethod")
@Retry(name = "inventory")
@TimeLimiter(name = "inventory")
public CompletableFuture<OrderResponse> placeOrder(OrderRequest orderRequest, String userId) {
    return CompletableFuture.supplyAsync(() -> {
        // ... toda la logica de negocio aqui adentro
        inventoryClient.reduceStock(sku, quantity);
        orderRepository.save(order);
        return orderMapper.toOrderResponse(savedOrder);
    });
}
```

### El Descubrimiento Critico: El "Hilo Zombie"

En arquitecturas sincronas (Spring MVC / Tomcat), el `TimeLimiter` cancela la espera del **cliente**, pero **NO mata el hilo del servidor** que esta ejecutando la operacion.

```
┌─────────────────────────────────────────────────────────────────┐
│  ADVERTENCIA ARQUITECTONICA                                     │
│                                                                 │
│  En Spring MVC, el TimeLimiter cancela la espera del cliente,   │
│  pero NO mata el hilo del servidor.                             │
│                                                                 │
│  Esto genera "Datos Fantasma":                                  │
│  El cliente recibe error, pero la operacion se completa         │
│  en segundo plano.                                              │
│                                                                 │
│  La solucion definitiva?                                        │
│  Arquitectura Orientada a Eventos (RabbitMQ)                    │
└─────────────────────────────────────────────────────────────────┘
```

**Secuencia del problema:**

1. Usuario hace POST para crear orden
2. `CompletableFuture.supplyAsync()` lanza la logica en un hilo separado
3. El TimeLimiter espera 3 segundos
4. Si `Inventory-Service` tarda mas de 3s, el TimeLimiter **cancela la espera** del hilo principal
5. El usuario recibe un error: "Timeout"
6. **PERO** el hilo del `CompletableFuture` **sigue ejecutandose en segundo plano**
7. Ese hilo termina de ejecutar `inventoryClient.reduceStock()` → **el stock se descuenta**
8. Ese hilo termina de ejecutar `orderRepository.save()` → **la orden se guarda en BD**
9. **Resultado:** El stock fue descontado y la orden fue creada, pero el usuario cree que fallo

**Esto es inconsistencia de datos.** El peor tipo de bug en sistemas distribuidos porque es silencioso y dificil de detectar.

---

## 5. Refactorizacion Final — Limpiando la arquitectura

### Concepto

Se elimino `@TimeLimiter` y `CompletableFuture` del flujo sincrono `placeOrder`. El metodo vuelve a ser sincrono puro, protegido solo por Circuit Breaker + Retry.

### Codigo final limpio: `OrderServiceImpl.java`

```java
// Fallback — retorno tipo sincrono (OrderResponse, no CompletableFuture)
public OrderResponse fallbackMethod(OrderRequest orderRequest, String userId, Throwable throwable){
    log.error("Circuit Breaker activado. Causa: {}", throwable.getMessage());
    throw new RuntimeException("El Servicio de Inventario no responde. Por favor intente mas tarde.");
}

@Override
@Transactional
@CircuitBreaker(name = "inventory", fallbackMethod = "fallbackMethod")
@Retry(name = "inventory")
public OrderResponse placeOrder(OrderRequest orderRequest, String userId) {
    if(!ordersEnabled){
        log.warn("Pedido rechazado: Servicio deshabilitado por configuracion.");
        throw new RuntimeException("Servicio de pedidos en mantenimiento");
    }

    log.info("Colocando nuevo pedido");
    Order order = orderMapper.toOrder(orderRequest);
    order.setUserId(userId);

    for(var item : order.getOrderLineItemsList()){
        String sku = item.getSku();
        Integer quantity = item.getQuantity();
        try {
            inventoryClient.reduceStock(sku, quantity);
        } catch (Exception e) {
            log.error("Error al reducir stock para el producto {}: {}", sku, e.getMessage());
            throw new IllegalArgumentException("No se pudo procesar la orden: Stock insuficiente o error de inventario");
        }
    }

    order.setOrderNumber(UUID.randomUUID().toString());
    Order savedOrder = orderRepository.save(order);
    log.info("Orden guardada con exito. ID: {}", savedOrder.getId());
    return orderMapper.toOrderResponse(savedOrder);
}
```

### Controller limpio: `OrderController.java`

```java
// Retorno sincrono — sin CompletableFuture
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public OrderResponse placeOrder(@Valid @RequestBody OrderRequest orderRequest,
                                @AuthenticationPrincipal Jwt jwt){
    return orderService.placeOrder(orderRequest, jwt.getSubject());
}
```

### Que se elimino y por que

| Eliminado | Razon |
|---|---|
| `@TimeLimiter(name = "inventory")` | Genera hilos zombie e inconsistencia de datos en MVC |
| `CompletableFuture<OrderResponse>` | Solo era necesario para el TimeLimiter |
| `CompletableFuture.supplyAsync(...)` | Ejecutaba logica transaccional fuera del hilo principal |
| Fallback con `CompletableFuture` | El fallback debe coincidir con el tipo de retorno del metodo |

---

## 6. La Trinidad de la Resiliencia

Los tres patrones trabajan juntos pero tienen responsabilidades distintas:

| Patron | Proposito | Analogia |
|---|---|---|
| **Circuit Breaker** | "Evita llamar si ya sabemos que esta roto" | Disyuntor electrico |
| **Retry** | "Intenta de nuevo si fue un parpadeo" | Insistencia elegante |
| **Timeout** | "No esperes por siempre" | Cronometro |

### Regla critica

> "Esperar consume mas recursos que fallar. Un buen arquitecto prefiere un error 503 rapido que un servidor colgado eternamente."

---

## 7. Conclusion — El limite de la sincronia HTTP

### Lo que aprendimos

La comunicacion sincrona HTTP tiene un **limite duro** para la consistencia transaccional:

1. **Circuit Breaker + Retry** resuelven el 90% de los problemas de resiliencia en la capa sincrona
2. **TimeLimiter en MVC** es peligroso: libera al usuario pero no al hilo del servidor, causando datos fantasma
3. La unica forma real de controlar operaciones de larga duracion con consistencia es con **mensajeria asincrona**

### El camino hacia la solucion real

```
Sincrono (HTTP)                          Asincrono (Mensajeria)
┌────────────────────┐                   ┌────────────────────┐
│ Order-Service      │                   │ Order-Service      │
│   ↓ HTTP call      │                   │   ↓ Publica evento │
│ Inventory-Service  │                   │ RabbitMQ / Kafka   │
│                    │                   │   ↓ Consume evento  │
│ Problema: si Inv   │                   │ Inventory-Service  │
│ tarda, Order se    │                   │                    │
│ bloquea o genera   │                   │ Order no espera.   │
│ datos fantasma     │                   │ Consistencia       │
│                    │                   │ eventual garantizada│
└────────────────────┘                   └────────────────────┘
```

**La sincronia HTTP tiene un limite. La solucion real para consistencia transaccional en microservicios es la mensajeria.**

---

## Archivos clave de esta seccion

| Archivo | Que hace |
|---|---|
| `order-service/pom.xml` | Dependencias: resilience4j, AOP, Actuator, WebFlux |
| `config-data/order-service.yml` | Config de Circuit Breaker, Retry (y TimeLimiter comentado) |
| `order-service/.../service/impl/OrderServiceImpl.java` | `@CircuitBreaker` + `@Retry` + fallback + codigo comentado del experimento TimeLimiter |
| `order-service/.../controller/OrderController.java` | Endpoint sincrono limpio (sin CompletableFuture) |
| `order-service/.../service/client/InventoryClient.java` | Cliente HTTP declarativo con `@PutExchange` via Eureka |
| `inventory-service/.../controller/InventoryController.java` | Endpoint `reduceStock` con `Thread.sleep` comentado (usado para simular latencia) |
