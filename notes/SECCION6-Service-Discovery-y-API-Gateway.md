# Seccion 6: Service Discovery & API Gateway

> Notas del curso de Microservicios — Descubrimiento de Servicios y Puerta de Enlace

---

## Objetivo de la seccion

Pasar de un conjunto de servicios aislados con URLs "quemadas" (`http://localhost:8082/...`) a un **ecosistema distribuido y escalable** donde:

- Los servicios se **descubren entre si** automaticamente (Eureka)
- Existe un **unico punto de entrada** para todos los clientes (API Gateway)
- El trafico se **balancea** entre multiples instancias de un mismo servicio
- Ya **nadie necesita saber IPs ni puertos** de los demas servicios

---

## 1. Netflix Eureka Server (Service Discovery)

### Concepto

Eureka es el "directorio telefonico" de tu arquitectura. Cada microservicio al arrancar le dice a Eureka: "Hola, soy `product-service` y estoy en el puerto 8080". Cuando otro servicio necesita hablar con el, le pregunta a Eureka: "¿Donde esta `product-service`?" y Eureka le da la direccion.

**Sin Eureka:** Cada servicio tiene que saber la IP y puerto de los demas (hardcoded).
**Con Eureka:** Solo necesita saber el NOMBRE del servicio.

### 1.1 Dependencia: `discovery-server/pom.xml`

```xml
<!-- Linea 35-37: Convierte este servicio en un Eureka Server -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-server</artifactId>
</dependency>

<!-- Linea 38-41: Web MVC para el dashboard visual de Eureka -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
```

- **`eureka-server`**: Contiene todo lo necesario para ser un servidor de descubrimiento: el registro, la interfaz web, los endpoints REST internos.
- **`webmvc`**: Eureka incluye un dashboard web donde ves todos los servicios registrados en `http://localhost:8761`.

### 1.2 Clase principal: `DiscoveryServerApplication.java`

```java
@SpringBootApplication
@EnableEurekaServer     // <-- Activa el servidor Eureka
public class DiscoveryServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(DiscoveryServerApplication.class, args);
    }
}
```

- **`@EnableEurekaServer`**: Sin esta anotacion, seria un Spring Boot normal. Con ella, expone el registro de servicios, acepta heartbeats de clientes, y muestra el dashboard.

### 1.3 Configuracion: `discovery-server/src/main/resources/application.yaml`

```yaml
spring:
  application:
    name: discovery-server     # Nombre del servicio

server:
  port: 8761                   # Puerto estandar para Eureka

eureka:
  client:
    register-with-eureka: false    # No se registra a si mismo (EL es el servidor)
    fetch-registry: false          # No necesita descargar el registro (EL es el registro)
```

**Linea por linea:**

- **`port: 8761`**: Puerto por convencion para Eureka. Todos los clientes buscan ahi por defecto.
- **`register-with-eureka: false`**: Eureka Server NO debe registrarse como cliente de si mismo. Sin esto, intentaria registrarse en su propio registry (loop inutil).
- **`fetch-registry: false`**: No necesita descargar la lista de servicios porque EL es quien la mantiene.

**Dashboard:** Al arrancar, entras a `http://localhost:8761` y ves una pagina web con todos los servicios registrados, su estado, sus instancias, etc.

### 1.4 Auditoria de seguridad en Eureka

Eureka trae dependencias transitivas (librerias que vienen incluidas automaticamente) que pueden tener **vulnerabilidades conocidas** (CVEs). En el curso se hizo una auditoria para:

- **Guava**: Libreria de Google que Eureka usa internamente. Versiones viejas tienen vulnerabilidades.
- **XStream**: Libreria de serializacion XML. Versiones viejas permiten ejecucion remota de codigo.

La solucion fue **forzar versiones seguras** o **excluir** estas dependencias en el `pom.xml` si no son necesarias. En la version actual del proyecto (Spring Cloud 2025.1.0) estas librerias ya vienen en versiones parcheadas.

---

## 2. Service Registration (Clientes Eureka)

### Concepto

Cada microservicio se convierte en un **Eureka Client**: al arrancar, se registra automaticamente en el servidor Eureka con su nombre y direccion. Eureka le devuelve la lista de todos los demas servicios registrados.

Ademas, cada cliente envia **heartbeats** (latidos) cada 30 segundos. Si Eureka deja de recibir heartbeats de un servicio, lo marca como DOWN y eventualmente lo elimina del registro.

### 2.1 Dependencia en cada servicio (pom.xml)

Todos los microservicios que se registran en Eureka tienen esta dependencia:

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
```

**Servicios que la tienen:**
- Product Service (`product-service/pom.xml`, linea 48-50)
- Order Service (`order-service/pom.xml`, linea 48-50)
- Inventory Service (`inventory-service/pom.xml`, linea 48-50)
- API Gateway (`api-gateway/pom.xml`, linea 59-61)

**Servicios que NO la tienen:**
- Discovery Server (el ES el servidor, no se registra como cliente)
- Config Server (no necesita ser descubierto por otros)
- Notification Service

### 2.2 Configuracion de Eureka en los archivos centralizados

La configuracion del cliente Eureka esta en los archivos de `config-data/` (gestionados por el Config Server):

```yaml
# config-data/product-service.yml
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/   # Donde esta el Eureka Server
```

```yaml
# config-data/order-service.yml
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
```

```yaml
# config-data/inventory-service.yml
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
```

```yaml
# config-data/api-gateway.yml
eureka:
  client:
    eureka-service-url:
      defaultZone: http://localhost:8761/eureka/
    fetch-registry: true           # Descarga la lista de servicios
    register-with-eureka: true     # Se registra en Eureka
```

**`defaultZone`**: La URL del Eureka Server. Todos los clientes apuntan a `http://localhost:8761/eureka/`.

### 2.3 Flujo del registro

```
1. Eureka Server arranca en :8761
            |
            v
2. product-service arranca
   -> Lee su config del Config Server
   -> Encuentra: eureka.client.service-url = http://localhost:8761/eureka/
   -> HTTP POST al Eureka Server: "Soy PRODUCT-SERVICE, estoy en :8080"
   -> Eureka lo registra en su tabla interna
            |
            v
3. order-service arranca
   -> Mismo proceso: "Soy ORDER-SERVICE, estoy en :8081"
            |
            v
4. inventory-service arranca
   -> Mismo proceso: "Soy INVENTORY-SERVICE, estoy en :8082"
            |
            v
5. Dashboard de Eureka (http://localhost:8761) muestra:

   Application          Status    Instances
   PRODUCT-SERVICE      UP        localhost:8080
   ORDER-SERVICE        UP        localhost:8081
   INVENTORY-SERVICE    UP        localhost:8082
   API-GATEWAY          UP        localhost:9000
```

### 2.4 Heartbeats (latidos)

```
Cada 30 segundos:

  product-service  ──heartbeat──>  Eureka Server
  order-service    ──heartbeat──>  Eureka Server
  inventory-service──heartbeat──>  Eureka Server

Si Eureka NO recibe heartbeat en 90 segundos:
  -> Marca el servicio como DOWN
  -> Eventualmente lo elimina del registro
  -> Los demas servicios dejan de enviarle trafico
```

---

## 3. Spring Cloud Gateway (Reactivo)

### Concepto

El API Gateway es el **unico punto de entrada** para todos los clientes externos. En lugar de que el frontend conozca las direcciones de cada microservicio (`product:8080`, `order:8081`, `inventory:8082`), solo conoce UNA: `gateway:9000`.

El Gateway recibe el request, mira la URL, y lo **enruta** al microservicio correcto.

**Sin Gateway:** El cliente necesita conocer la IP/puerto de cada servicio.
**Con Gateway:** El cliente solo habla con `localhost:9000`, el Gateway decide a donde va.

### 3.1 Dependencia: `api-gateway/pom.xml`

```xml
<!-- Linea 52-54: El Gateway reactivo basado en Netty/WebFlux -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-gateway-server-webflux</artifactId>
</dependency>

<!-- Linea 56-58: Load Balancer para distribuir trafico -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>

<!-- Linea 59-61: Cliente Eureka para descubrir servicios -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
```

**Por que WebFlux y no MVC?** El Gateway usa un modelo **reactivo** (no bloqueante). Esto es critico porque el Gateway maneja TODAS las peticiones del sistema — si fuera bloqueante (MVC), un servicio lento bloquearia hilos y afectaria a todos los demas. Con WebFlux, puede manejar miles de conexiones concurrentes sin bloquearse.

### 3.2 Rutas en Java: `api-gateway/.../config/GatewayConfig.java`

```java
@Configuration
public class GatewayConfig {

    @Bean
    public RouteLocator routeLocator(RouteLocatorBuilder builder){
        return builder.routes()

            // Ruta 1: Todo /api/v1/product/** va al Product Service
            .route("product-service", r -> r
                    .path("/api/v1/product/**")         // Predicate: si la URL coincide...
                    .uri("lb://PRODUCT-SERVICE"))       // ...envia a este destino

            // Ruta 2: Todo /api/v1/order/** va al Order Service
            .route("order-service", r -> r
                    .path("/api/v1/order/**")
                    .uri("lb://ORDER-SERVICE"))

            // Ruta 3: Todo /api/v1/inventory/** va al Inventory Service
            .route("inventory-service", r -> r
                    .path("/api/v1/inventory/**")
                    .uri("lb://INVENTORY-SERVICE"))

            .build();
    }
}
```

**Linea por linea:**

- **`.route("product-service", ...)`**: Define una ruta con ID `product-service` (para identificarla en logs).
- **`.path("/api/v1/product/**")`**: **Predicate** (condicion). Si la URL del request empieza con `/api/v1/product/`, esta ruta se activa.
- **`.uri("lb://PRODUCT-SERVICE")`**: **Destino**. El prefijo `lb://` es la clave:
  - `lb://` = "usa el Load Balancer"
  - `PRODUCT-SERVICE` = nombre registrado en Eureka (no una IP/puerto)
  - El Load Balancer le pregunta a Eureka: "¿Donde esta PRODUCT-SERVICE?" y obtiene la(s) direccion(es)

**Sin `lb://`** tendrias que poner `http://localhost:8080` (hardcoded). Si la IP cambia o levantas mas instancias, todo se rompe.

### 3.3 Rutas en YAML: `config-data/api-gateway.yml`

Las rutas tambien se pueden definir en la configuracion centralizada (en lugar de o ademas de Java):

```yaml
cloud:
  gateway:
    default-filters:
      - TokenRelay                         # Filtro global (visto en seccion 7)

    routes:
      - id: product-service                # ID de la ruta
        uri: lb://product-service          # Destino con Load Balancer
        predicates:
          - Path=/api/v1/product/**        # Condicion de match

      - id: order-service
        uri: lb://order-service
        predicates:
          - Path=/api/v1/order/**

      - id: inventory-service
        uri: lb://inventory-service
        predicates:
          - Path=/api/v1/inventory/**
```

**¿Por que hay rutas en Java Y en YAML?** Ambas funcionan. La version Java (`GatewayConfig.java`) se definio primero. Luego, al integrar el Gateway con el Config Server, se movieron las rutas al YAML centralizado para poder modificarlas sin recompilar. Ambas coexisten; Spring las combina.

### 3.4 Flujo de un request a traves del Gateway

```
Cliente (Postman/Frontend)
    |
    | GET http://localhost:9000/api/v1/product
    v
┌────────────────────────────────────────────┐
│           API GATEWAY (:9000)              │
│                                            │
│  1. Recibe el request                      │
│  2. Evalua predicates:                     │
│     ¿/api/v1/product/** coincide? -> SI    │
│  3. Destino: lb://PRODUCT-SERVICE          │
│  4. Pregunta a Eureka:                     │
│     "¿Donde esta PRODUCT-SERVICE?"         │
│  5. Eureka responde:                       │
│     "Esta en localhost:8080"               │
│  6. Reenvia el request a localhost:8080    │
└────────────────────────────────────────────┘
    |
    v
Product Service (:8080) responde
    |
    v
Gateway le devuelve la respuesta al cliente
```

---

## 4. Enrutamiento Dinamico (Client-Side Load Balancing)

### Concepto

El **Load Balancer** distribuye las peticiones entre multiples instancias de un mismo servicio. Si tienes 3 copias de `product-service` corriendo, el balanceador reparte el trafico entre las 3.

**Importante:** Es **Client-Side** Load Balancing — el balanceo lo hace el CLIENTE (Gateway o el servicio que llama), no un servidor central. El cliente le pregunta a Eureka por todas las instancias disponibles y elige una.

### 4.1 El protocolo `lb://`

```
lb://PRODUCT-SERVICE
 |       |
 |       └── Nombre del servicio en Eureka
 └── "Usa Load Balancer" (no conectes directo)
```

Cuando el Gateway (o cualquier servicio) ve `lb://PRODUCT-SERVICE`:
1. Le pide a Eureka la lista de instancias de `PRODUCT-SERVICE`
2. Eureka responde: `[localhost:8080, localhost:54321, localhost:62108]`
3. El Load Balancer elige una (por defecto Round Robin: primera, segunda, tercera, primera...)
4. Envia el request a la instancia elegida

### 4.2 Dependencia: `spring-cloud-starter-loadbalancer`

```xml
<!-- api-gateway/pom.xml, linea 56-58 -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
```

Tambien en `order-service/pom.xml` (para balancear las llamadas a inventory-service):

```xml
<!-- order-service/pom.xml -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
```

---

## 5. Escalabilidad Horizontal

### Concepto

Puedes levantar **multiples copias** del mismo microservicio. Cada copia se registra en Eureka como una instancia diferente, y el Load Balancer reparte el trafico entre ellas.

### 5.1 Puerto aleatorio

Para levantar multiples instancias sin conflicto de puertos, usas `server.port=0`:

```yaml
server:
  port: 0     # Spring Boot asigna un puerto aleatorio disponible
```

Con puerto fijo (`port: 8080`), solo puedes tener UNA instancia. Con `port: 0`, cada instancia obtiene un puerto diferente (ej: 54321, 62108, etc.).

### 5.2 Instance ID unico

Eureka necesita identificar cada instancia de forma unica. Por defecto usa `hostname:application-name:port`, pero con puerto 0 necesitas un ID unico:

```yaml
eureka:
  instance:
    instance-id: ${spring.application.name}:${random.uuid}
```

Esto genera IDs como: `product-service:a1b2c3d4-e5f6-7890`

### 5.3 Ejemplo visual

```
Eureka Dashboard:

Application          Instances
PRODUCT-SERVICE      product-service:a1b2c3d4 (port 54321)
                     product-service:e5f6g7h8 (port 62108)
                     product-service:i9j0k1l2 (port 48990)
ORDER-SERVICE        order-service:m3n4o5p6  (port 8081)
INVENTORY-SERVICE    inventory-service:q7r8  (port 8082)

El Gateway balancea entre las 3 instancias de product-service:
  Request 1 → port 54321
  Request 2 → port 62108
  Request 3 → port 48990
  Request 4 → port 54321  (vuelve a empezar: Round Robin)
```

---

## 6. Comunicacion Interna Resiliente (Order → Inventory)

### Concepto

El `order-service` necesita hablar con `inventory-service` para verificar/reducir stock. Antes de Eureka, esto era con URLs hardcoded: `http://localhost:8082/api/v1/inventory/...`. Ahora usa el nombre del servicio + Load Balancer.

### 6.1 WebClient con @LoadBalanced: `order-service/.../config/WebClientConfig.java`

```java
@Configuration
public class WebClientConfig {

    @Bean
    @LoadBalanced    // <-- La clave: habilita la resolucion de nombres via Eureka
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    public InventoryClient inventoryClient(WebClient.Builder builder){
        // Usa el NOMBRE del servicio en Eureka, no una IP/puerto
        WebClient webClient = builder
                .baseUrl("http://INVENTORY-SERVICE")    // <-- Nombre de Eureka, no localhost:8082
                .build();

        // Crea un cliente HTTP declarativo a partir de la interfaz InventoryClient
        HttpServiceProxyFactory factory =
                HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient)).build();

        return factory.createClient(InventoryClient.class);
    }
}
```

**Linea por linea:**

- **`@LoadBalanced`** (linea 15): Esta anotacion en el `WebClient.Builder` le dice a Spring: "Cuando veas una URL como `http://INVENTORY-SERVICE/...`, no intentes resolver `INVENTORY-SERVICE` como un hostname DNS. En su lugar, preguntale a Eureka donde esta y balancea el trafico."
  - Sin `@LoadBalanced`: Spring intenta resolver `INVENTORY-SERVICE` como DNS → falla.
  - Con `@LoadBalanced`: Spring consulta Eureka → obtiene `localhost:8082` → conecta.

- **`baseUrl("http://INVENTORY-SERVICE")`** (linea 23): El nombre `INVENTORY-SERVICE` es exactamente como el servicio se registro en Eureka. El `@LoadBalanced` intercepta esta URL y la resuelve dinamicamente.

- **`HttpServiceProxyFactory`** (lineas 25-28): Crea una implementacion automatica de la interfaz `InventoryClient`. Spring genera el codigo HTTP por ti basandose en las anotaciones de la interfaz.

### 6.2 Interfaz declarativa: `order-service/.../service/client/InventoryClient.java`

```java
public interface InventoryClient {

    @PutExchange("/api/v1/inventory/reduce/{sku}")
    String reduceStock(@PathVariable String sku, @RequestParam Integer quantity);
}
```

**Linea por linea:**

- **`InventoryClient`**: Es una interfaz (no una clase). No escribes la implementacion — Spring la genera automaticamente usando `HttpServiceProxyFactory`.
- **`@PutExchange`**: Similar a `@PutMapping` pero del lado del CLIENTE. Define que este metodo hara un `HTTP PUT` al path indicado.
- **`/api/v1/inventory/reduce/{sku}`**: La ruta en el Inventory Service. El `{sku}` se reemplaza con el valor de `@PathVariable`.
- **`@RequestParam Integer quantity`**: Se envia como query parameter: `?quantity=5`.

**Resultado:** Cuando el Order Service llama `inventoryClient.reduceStock("SKU-123", 5)`, se traduce a:

```
HTTP PUT http://INVENTORY-SERVICE/api/v1/inventory/reduce/SKU-123?quantity=5
         |                        |
         |                        └── Resuelto por Eureka a localhost:8082
         └── Interceptado por @LoadBalanced
```

### 6.3 Como se usa en OrderServiceImpl

```java
// order-service/.../service/impl/OrderServiceImpl.java
for(var item : order.getOrderLineItemsList()){
    String sku = item.getSku();
    Integer quantity = item.getQuantity();

    try {
        // Antes (hardcoded):
        // webClientBuilder.build().put()
        //     .uri("http://localhost:8082/api/v1/inventory/reduce/" + sku, ...)
        //     .retrieve().bodyToMono(String.class).block();

        // Ahora (con Eureka + LoadBalancer):
        inventoryClient.reduceStock(sku, quantity);

    } catch (Exception e) {
        throw new IllegalArgumentException(
            "No se pudo procesar la orden: Stock insuficiente o error de inventario"
        );
    }
}
```

El codigo comentado muestra el **antes**: URL hardcoded a `localhost:8082`. El codigo actual usa `inventoryClient` que resuelve el servicio via Eureka.

---

## 7. Integracion Total: Gateway + Config Server

### Concepto

El API Gateway tambien es un Config Client. Sus rutas estan definidas en `config-data/api-gateway.yml` (en el repo Git centralizado), no hardcoded en el codigo.

### 7.1 El Gateway como Config Client

```yaml
# api-gateway/src/main/resources/application.yaml
spring:
  application:
    name: api-gateway
  config:
    import: "optional:configserver:http://localhost:8888"
```

Igual que los demas servicios, el Gateway al arrancar:
1. Se conecta al Config Server en `:8888`
2. Pide su configuracion (`api-gateway.yml`)
3. Recibe: rutas, OAuth2 config, TokenRelay, Eureka, etc.

### 7.2 Beneficio: cambiar rutas sin recompilar

Si necesitas agregar un nuevo microservicio o cambiar un path, solo modificas `config-data/api-gateway.yml` en GitHub:

```yaml
routes:
  # Agregar nueva ruta (sin tocar codigo Java):
  - id: payment-service
    uri: lb://payment-service
    predicates:
      - Path=/api/v1/payment/**
```

Y haces `POST /actuator/refresh` para que el Gateway tome la nueva ruta.

---

## 8. Resumen Visual — Arquitectura Completa

```
                    Clientes (Postman, Frontend, Mobile)
                              |
                              | Todas las peticiones van a :9000
                              v
                   ┌─────────────────────┐
                   │   API GATEWAY       │
                   │   (:9000)           │
                   │                     │
                   │ Predicates:         │
                   │ /product/** → lb:// │──── Eureka: "¿Donde esta
                   │ /order/**   → lb:// │     PRODUCT-SERVICE?"
                   │ /inventory/**→ lb://│
                   └─────────┬───────────┘
                             |
              ┌──────────────┼──────────────┐
              |              |              |
              v              v              v
       ┌──────────┐  ┌──────────┐  ┌──────────────┐
       │ PRODUCT  │  │  ORDER   │  │  INVENTORY   │
       │ SERVICE  │  │ SERVICE  │  │   SERVICE    │
       │ (:8080)  │  │ (:8081)  │  │   (:8082)   │
       │          │  │          │  │              │
       │ MongoDB  │  │PostgreSQL│  │    MySQL     │
       └──────────┘  └────┬─────┘  └──────────────┘
                          |              ^
                          |              |
                          └──────────────┘
                    inventoryClient.reduceStock()
                    via @LoadBalanced + Eureka
                    (ya no usa localhost:8082)


    Todos los servicios estan registrados en:

       ┌─────────────────────────────────┐
       │      EUREKA SERVER (:8761)      │
       │                                 │
       │  PRODUCT-SERVICE    UP (1)      │
       │  ORDER-SERVICE      UP (1)      │
       │  INVENTORY-SERVICE  UP (1)      │
       │  API-GATEWAY        UP (1)      │
       │                                 │
       │  Heartbeats cada 30s            │
       │  Baja servicios tras 90s sin    │
       │  respuesta                      │
       └─────────────────────────────────┘
```

---

## 9. Tabla: Dependencias por servicio

| Servicio | eureka-server | eureka-client | loadbalancer | gateway-webflux | config-client |
|---|---|---|---|---|---|
| **Discovery Server** | Si | No | No | No | No |
| **API Gateway** | No | Si | Si | Si | Si |
| **Product Service** | No | Si | No | No | Si |
| **Order Service** | No | Si | Si | No | Si |
| **Inventory Service** | No | Si | No | No | Si |
| **Config Server** | No | No | No | No | No |

- **Order Service tiene `loadbalancer`** porque hace llamadas directas a Inventory Service via WebClient + `@LoadBalanced`.
- **Product e Inventory NO tienen `loadbalancer`** porque no llaman a otros servicios (solo reciben requests).

---

## 10. Antes vs Despues

### Comunicacion Order → Inventory

| | Antes | Despues |
|---|---|---|
| **URL** | `http://localhost:8082/api/v1/inventory/reduce/SKU-1` | `http://INVENTORY-SERVICE/api/v1/inventory/reduce/SKU-1` |
| **¿Que pasa si cambia el puerto?** | Se rompe | Eureka resuelve automaticamente |
| **¿Que pasa si hay 3 instancias?** | Solo va a una | Load Balancer reparte entre las 3 |
| **¿Como se implementa?** | `webClientBuilder.uri("http://localhost:8082/...")` | `inventoryClient.reduceStock(sku, qty)` |

### Acceso del cliente externo

| | Antes | Despues |
|---|---|---|
| **Productos** | `http://localhost:8080/api/v1/product` | `http://localhost:9000/api/v1/product` |
| **Ordenes** | `http://localhost:8081/api/v1/order` | `http://localhost:9000/api/v1/order` |
| **Inventario** | `http://localhost:8082/api/v1/inventory` | `http://localhost:9000/api/v1/inventory` |
| **Puertos a conocer** | 3 (uno por servicio) | 1 (solo el Gateway) |

---

## 11. Archivos clave de esta seccion

| Archivo | Que hace |
|---|---|
| `discovery-server/pom.xml` | Dependencia `eureka-server` |
| `discovery-server/.../DiscoveryServerApplication.java` | `@EnableEurekaServer` — activa Eureka |
| `discovery-server/src/main/resources/application.yaml` | Puerto 8761, no se registra a si mismo |
| `api-gateway/pom.xml` | Deps: `gateway-webflux` + `loadbalancer` + `eureka-client` |
| `api-gateway/.../config/GatewayConfig.java` | Rutas en Java: `lb://PRODUCT-SERVICE`, etc. |
| `config-data/api-gateway.yml` | Rutas en YAML centralizado + Eureka + OAuth2 |
| `order-service/.../config/WebClientConfig.java` | `@LoadBalanced` WebClient + `InventoryClient` proxy |
| `order-service/.../service/client/InventoryClient.java` | Interfaz declarativa: `@PutExchange` a inventory |
| `config-data/product-service.yml` | `eureka.client.service-url` para registrarse |
| `config-data/order-service.yml` | `eureka.client.service-url` para registrarse |
| `config-data/inventory-service.yml` | `eureka.client.service-url` para registrarse |
