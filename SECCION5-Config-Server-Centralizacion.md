# Seccion 5: Config Server (Centralizacion)

> Notas del curso de Microservicios — Centralizacion de Configuracion

---

## Objetivo de la seccion

Pasar de tener configuraciones "quemadas" (hardcoded) dentro de cada microservicio a una **arquitectura centralizada** donde:

- Un **Config Server** es la fuente unica de verdad para toda la configuracion
- Los archivos `.yml` viven en un **repositorio Git** (versionados, auditables)
- Los microservicios **delegan** su configuracion al servidor central al arrancar
- Se pueden cambiar propiedades **sin reiniciar** los servicios (Hot Reload)

Esto sigue el principio de las **12-Factor Apps**: separacion estricta entre codigo y configuracion.

---

## 1. El Servidor de Configuraciones

### Concepto

El Config Server es un microservicio cuyo unico trabajo es **servir archivos de configuracion** a los demas servicios. En lugar de que cada microservicio tenga su propio `application.yml` completo con BD, puertos, credenciales, etc., todos le preguntan al Config Server: "Dame MI configuracion".

### 1.1 Dependencia: `config-server/pom.xml`

```xml
<!-- Linea 39-41: La libreria que convierte este servicio en un Config Server -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-config-server</artifactId>
</dependency>
```

**Para que:** Esta sola dependencia le da al servicio la capacidad de leer archivos de configuracion desde un backend (en nuestro caso Git) y servirlos via HTTP a los clientes.

### 1.2 Clase principal: `ConfigServerApplication.java`

```java
@SpringBootApplication
@EnableConfigServer       // <-- Esta anotacion es la que activa todo
public class ConfigServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
```

- **`@EnableConfigServer`**: Le dice a Spring: "Este servicio es un Config Server. Expone endpoints HTTP para que otros servicios pidan su configuracion."
- Sin esta anotacion, seria un servicio Spring Boot normal que no sirve configuraciones a nadie.

**Endpoints que se exponen automaticamente:**
- `GET http://localhost:8888/product-service/default` -> devuelve la config de product-service
- `GET http://localhost:8888/order-service/default` -> devuelve la config de order-service
- `GET http://localhost:8888/inventory-service/default` -> devuelve la config de inventory-service

### 1.3 Configuracion: `config-server/src/main/resources/application.yaml`

```yaml
spring:
  application:
    name: config-server      # Nombre del servicio

  cloud:
    config:
      server:
        git:
          # URL del repositorio Git donde estan los archivos de configuracion
          uri: https://github.com/OscarRuiz21/microservice-config-data

          # Credenciales para acceder al repo privado (via variables de entorno)
          username: ${GITHUB_USER}
          password: ${GITHUB_TOKEN}

          # Clona el repo al arrancar (no espera al primer request)
          clone-on-start: true

          # Rama de Git de donde lee
          default-label: main

server:
  port: 8888    # Puerto estandar para Config Servers
```

**Linea por linea:**

- **`uri`**: El Config Server NO tiene los archivos `.yml` localmente. Los lee de este repositorio de GitHub. Si cambias un archivo ahi, el Config Server sirve la version nueva.
- **`username / password`**: Como el repo es **privado**, necesita credenciales. Se pasan como variables de entorno (`${GITHUB_USER}`, `${GITHUB_TOKEN}`) para no exponer secretos en el codigo.
- **`clone-on-start: true`**: Al arrancar, el servidor clona el repo completo. Sin esto, esperaria al primer request para clonarlo (seria mas lento la primera vez).
- **`default-label: main`**: Lee de la rama `main`. Podrias tener ramas como `develop`, `staging`, `production` con configs diferentes.
- **`port: 8888`**: Puerto por convencion para Config Servers en Spring Cloud.

---

## 2. Backend con Git (Versionado)

### Concepto

Los archivos de configuracion viven en un repositorio Git (`microservice-config-data`). Esto da tres ventajas enormes:

1. **Versionado**: Puedes ver quien cambio que, cuando, y hacer rollback
2. **Auditoria**: Git log te dice exactamente que cambio y por que
3. **Seguridad**: El repo es privado, solo el Config Server tiene acceso

### 2.1 Estructura del repositorio de configuracion: `config-data/`

```
config-data/
  ├── application.yml          # Configuracion GLOBAL (compartida por todos)
  ├── product-service.yml      # Config exclusiva del Product Service
  ├── order-service.yml        # Config exclusiva del Order Service
  ├── inventory-service.yml    # Config exclusiva del Inventory Service
  └── api-gateway.yml          # Config exclusiva del API Gateway
```

**Convencion de nombres:** El nombre del archivo DEBE coincidir con el `spring.application.name` del microservicio. Cuando `product-service` pide su config, el Config Server busca `product-service.yml`.

### 2.2 Ejemplo: `config-data/product-service.yml`

```yaml
spring:
  threads:
    virtual:
      enabled: true                    # Habilita hilos virtuales (Java 21)
  data:
    mongodb:
      host: localhost
      port: 27017
      database: product-db
      username: root
      password: password
      authentication-database: admin

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/   # Registro en Eureka

app:
  maintenance:
    message: "ATENCION MANTENIMENTO PROGRAMADO 22:00 hrs."  # <-- Propiedad dinamica

management:
  endpoints:
    web:
      exposure:
        include: "refresh, health"     # Expone endpoint /refresh para Hot Reload

server:
  port: 8080
  error:
    include-message: always
    include-stacktrace: never
```

**Para que:** TODA la configuracion del Product Service esta aqui, no en su propio `application.yaml`. Conexion a MongoDB, puerto, Eureka, Actuator... todo centralizado.

### 2.3 Ejemplo: `config-data/order-service.yml`

```yaml
spring:
  threads:
    virtual:
      enabled: true
  datasource:
    url: jdbc:postgresql://localhost:5432/order-db
    username: admin
    password: admin
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.PostgreSQLDialect
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:8080/realms/ecommerce-realm

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/

order:
  enabled: true                        # <-- Feature Flag: Boton de panico

management:
  endpoints:
    web:
      exposure:
        include: "refresh, health"

server:
  port: 8081
```

### 2.4 Ejemplo: `config-data/inventory-service.yml`

```yaml
spring:
  threads:
    virtual:
      enabled: true
  datasource:
    url: jdbc:mysql://localhost:3306/inventory-db
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQLDialect

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/

inventory:
  allow-backorders: true               # <-- Feature Flag: Modo venta infinito

management:
  endpoints:
    web:
      exposure:
        include: "refresh, health"

server:
  port: 8082
```

---

## 3. Spring Cloud Config Client

### Concepto

Cada microservicio se convierte en un **Config Client**: al arrancar, en lugar de leer su propia configuracion local, le pregunta al Config Server "dame mi configuracion". El Config Server busca el archivo que coincida con su nombre y se la devuelve.

### 3.1 Dependencia en cada servicio (pom.xml)

Cada microservicio que usa Config Server tiene esta dependencia:

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-config</artifactId>
</dependency>
```

**Servicios que la tienen:** Product Service, Order Service, Inventory Service, API Gateway
**Servicios que NO la tienen:** Discovery Server (Eureka), Notification Service, Config Server (el no se pide config a si mismo)

### 3.2 El `application.yaml` local de cada servicio (antes vs despues)

**ANTES (sin Config Server):** El `application.yaml` tenia TODO — BD, puerto, Eureka, etc.

**DESPUES (con Config Server):** El `application.yaml` queda minimo — solo lo necesario para encontrar al Config Server:

```yaml
# product-service/src/main/resources/application.yaml
spring:
  application:
    name: product-service                                    # 1. ¿Quien soy?
  config:
    import: "optional:configserver:http://localhost:8888"     # 2. ¿Donde esta mi config?
```

```yaml
# order-service/src/main/resources/application.yaml
spring:
  application:
    name: order-service
  config:
    import: "optional:configserver:http://localhost:8888"
```

```yaml
# inventory-service/src/main/resources/application.yaml
spring:
  application:
    name: inventory-service
  config:
    import: "optional:configserver:http://localhost:8888"
```

```yaml
# api-gateway/src/main/resources/application.yaml
spring:
  application:
    name: api-gateway
  config:
    import: "optional:configserver:http://localhost:8888"
```

**Linea por linea:**

- **`spring.application.name`**: Es CRITICO. El Config Server usa este nombre para buscar el archivo de configuracion correspondiente. Si el nombre es `product-service`, busca `product-service.yml` en el repo Git.
- **`spring.config.import`**: Le dice a Spring: "Importa mi configuracion desde este Config Server".
- **`optional:`**: El prefijo `optional` significa: "Si el Config Server no esta disponible, arranca de todos modos con lo que tengas localmente". Sin `optional`, el servicio fallaria al arrancar si el Config Server esta caido.

### 3.3 Flujo de arranque

```
product-service arranca
        |
        v
Lee application.yaml local
  -> name = "product-service"
  -> config.import = "configserver:http://localhost:8888"
        |
        v
HTTP GET http://localhost:8888/product-service/default
        |
        v
Config Server recibe el request
  -> Busca "product-service.yml" en el repo Git
  -> Lo devuelve como JSON
        |
        v
product-service recibe la config
  -> Aplica: puerto 8080, MongoDB localhost:27017, Eureka, etc.
  -> Arranca normalmente con la config centralizada
```

### 3.4 Servicios que NO usan Config Server

- **Discovery Server (Eureka)**: No depende del Config Server porque es infraestructura base. Si Eureka dependiera del Config Server y el Config Server dependiera de Eureka, tendriamos un **deadlock circular**.
- **Notification Service**: Configurado de forma independiente.
- **Config Server**: No se pide configuracion a si mismo.

---

## 4. Hot Reload & @RefreshScope

### Concepto

Normalmente, si cambias una propiedad en el archivo de configuracion, tendrias que **reiniciar** el microservicio para que tome el nuevo valor. Con `@RefreshScope` + Actuator, puedes actualizar valores **en caliente** (sin reiniciar, sin downtime).

### 4.1 Actuator: Exponer el endpoint `/refresh`

En cada archivo de configuracion centralizado:

```yaml
# config-data/product-service.yml (y en los demas)
management:
  endpoints:
    web:
      exposure:
        include: "refresh, health"
```

**Para que:** Expone el endpoint `POST /actuator/refresh`. Al llamarlo, el servicio:
1. Se reconecta al Config Server
2. Pide su configuracion actualizada
3. Compara con la que tenia
4. Recrea los beans marcados con `@RefreshScope` con los nuevos valores

### 4.2 `@RefreshScope` en ProductController

```java
// product-service/.../controller/ProductController.java

@RestController
@RequestMapping("/api/v1/product")
@RequiredArgsConstructor
@RefreshScope                          // <-- Este bean se RECREA cuando llamas /refresh
public class ProductController {

    private final ProductService productService;

    // Lee la propiedad del Config Server
    @Value("${app.maintenance.message:Sistema Operativo}")
    private String maintenanceMessage;  // <-- Este valor se actualiza sin reiniciar

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<ProductResponseDTO> getAllProducts(HttpServletResponse response) {
        // Agrega el mensaje como header HTTP de la respuesta
        response.addHeader("X-Maintenance-Message", maintenanceMessage);
        return productService.getAllsProducts();
    }
}
```

**Linea por linea:**

- **`@RefreshScope`** (linea 19): Marca todo el controller como "refrescable". Cuando llamas `POST /actuator/refresh`, Spring destruye este bean y lo crea de nuevo, re-leyendo el `@Value`.
- **`@Value("${app.maintenance.message:Sistema Operativo}")`** (linea 24): Lee la propiedad `app.maintenance.message` del Config Server. Si no existe, usa el default `"Sistema Operativo"`.
- **`response.addHeader("X-Maintenance-Message", maintenanceMessage)`** (linea 36): Pone el mensaje como header HTTP. Asi el frontend o Postman puede leerlo.

### 4.3 Flujo del Hot Reload

```
1. Valor actual: "ATENCION MANTENIMENTO PROGRAMADO 22:00 hrs."
        |
        v
2. Cambias el archivo en GitHub:
   app.maintenance.message: "Sistema funcionando normalmente"
        |
        v
3. Haces POST http://localhost:8080/actuator/refresh
        |
        v
4. Spring destruye el ProductController actual
   -> Crea uno nuevo
   -> Lee el @Value de nuevo del Config Server
   -> maintenanceMessage = "Sistema funcionando normalmente"
        |
        v
5. El siguiente GET /api/v1/product ya trae el nuevo header
   SIN haber reiniciado el servicio
```

---

## 5. Feature Flags (Casos de Uso Real)

### Concepto

Los Feature Flags son propiedades booleanas en la configuracion que activan o desactivan funcionalidad **sin tocar el codigo**. Cambias un `true` por un `false` en el archivo de configuracion, llamas `/refresh`, y la funcionalidad cambia en tiempo real.

### 5.1 Modo Venta Infinito — Inventory Service

**El problema:** Hay momentos (como Black Friday) donde quieres aceptar TODAS las ordenes sin importar si hay stock fisico o no. Pero no quieres modificar y redesplegar codigo.

**La solucion:** Un flag `inventory.allow-backorders`.

**Configuracion:** `config-data/inventory-service.yml`

```yaml
inventory:
  allow-backorders: true    # true = acepta todo, false = valida stock real
```

**Codigo:** `inventory-service/.../service/impl/InventoryServiceImpl.java`

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryServiceImpl implements InventoryService {
    private final InventoryRepository inventoryRepository;
    private final InventoryMapper inventoryMapper;

    // Lee el flag del Config Server. Default: false (valida stock normalmente)
    @Value("${inventory.allow-backorders:false}")
    private boolean allowBackorders;

    @Override
    @Transactional(readOnly = true)
    public boolean isInStock(String sku, Integer quantity) {

        // Si el flag esta activo, SIEMPRE dice que hay stock
        if(allowBackorders){
            log.warn("MODO BACKORDER ACTIVO: Autorizando stock para SKU: {}", sku);
            return true;      // <-- Venta infinita: no valida inventario real
        }

        // Comportamiento normal: revisa la BD
        return inventoryRepository.findBySku(sku)
                .map(inventory -> inventory.getQuantity() >= quantity)
                .orElse(false);
    }
    // ... resto de metodos
}
```

**Flujo:**

```
allowBackorders = true (en config-data/inventory-service.yml)
        |
        v
Cliente intenta comprar 100 unidades de "SKU-123"
        |
        v
isInStock("SKU-123", 100)
        |
        v
if(allowBackorders) --> true
   -> log.warn("MODO BACKORDER ACTIVO...")
   -> return true (se acepta la orden sin importar stock)
```

**Para desactivar:** Cambias `allow-backorders: false` en GitHub, haces `POST /actuator/refresh`, y el servicio vuelve a validar stock real.

> **Nota:** Este servicio NO tiene `@RefreshScope`, asi que el flag solo se lee al arrancar. Para que se actualice en caliente sin reiniciar, habria que agregarle `@RefreshScope` a la clase.

### 5.2 Boton de Panico — Order Service

**El problema:** Algo salio mal en produccion (BD lenta, fraude masivo, error critico). Necesitas **detener TODAS las ordenes inmediatamente** sin apagar el servidor ni desplegar codigo nuevo.

**La solucion:** Un flag `order.enabled`.

**Configuracion:** `config-data/order-service.yml`

```yaml
order:
  enabled: true     # true = acepta ordenes, false = PANICO: rechaza todo
```

**Codigo:** `order-service/.../service/impl/OrderServiceImpl.java`

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {
    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final InventoryClient inventoryClient;

    // Lee el flag del Config Server. Default: false (servicio apagado por seguridad)
    @Value("${order.enabled:false}")
    private boolean ordersEnabled;

    @Override
    @Transactional
    public OrderResponse placeOrder(OrderRequest orderRequest, String userId) {

        // PRIMERA LINEA del metodo: revisa el flag antes de hacer cualquier cosa
        if(!ordersEnabled){
            log.warn("Pedido rechazado: Servicio dehabilitado por configuracion.");
            throw new RuntimeException(
                "Servicio de pedidos en mantenimiento, favor de contactar a soporte"
            );
        }

        // Si el flag esta activo, continua con la logica normal...
        log.info("Colocando nuevo pedido");
        Order order = orderMapper.toOrder(orderRequest);
        order.setUserId(userId);
        // ... validar inventario, guardar orden, etc.
    }
}
```

**Flujo del boton de panico:**

```
1. SITUACION NORMAL: order.enabled = true
   -> Las ordenes se procesan normalmente

2. ¡EMERGENCIA! Cambias en GitHub: order.enabled = false
   -> POST http://localhost:8081/actuator/refresh

3. Cualquier intento de crear una orden:
   -> placeOrder() se ejecuta
   -> if(!ordersEnabled) --> true (esta deshabilitado)
   -> throw RuntimeException("Servicio en mantenimiento...")
   -> El cliente recibe un error claro
   -> NINGUNA orden se procesa

4. Se resuelve la emergencia: order.enabled = true
   -> POST /actuator/refresh
   -> Las ordenes vuelven a fluir
```

**El default `false` es intencional:** `@Value("${order.enabled:false}")` — si por algun motivo el Config Server no esta disponible y el servicio arranca sin config, el default es `false` (rechazar ordenes). Es mas seguro rechazar ordenes por error que procesar ordenes incorrectamente.

> **Misma nota:** Este servicio tampoco tiene `@RefreshScope`, asi que el flag se lee al arrancar. Para hot reload real necesitaria `@RefreshScope`.

---

## 6. Resumen Visual — Arquitectura del Config Server

```
┌──────────────────────────────────────────────────────────┐
│              GITHUB (Repo Privado)                       │
│         microservice-config-data                         │
│  ├── application.yml         (global)                    │
│  ├── product-service.yml     (MongoDB, puerto, mensaje)  │
│  ├── order-service.yml       (PostgreSQL, JWT, flag)     │
│  ├── inventory-service.yml   (MySQL, flag backorders)    │
│  └── api-gateway.yml         (OAuth2, rutas, TokenRelay) │
└────────────────────┬─────────────────────────────────────┘
                     │ git clone (al arrancar)
                     ▼
┌──────────────────────────────────────────────────────────┐
│           CONFIG SERVER (:8888)                          │
│    @EnableConfigServer                                   │
│    Credenciales: ${GITHUB_USER} / ${GITHUB_TOKEN}        │
│                                                          │
│    Endpoints automaticos:                                │
│    GET /product-service/default → product-service.yml    │
│    GET /order-service/default   → order-service.yml      │
│    GET /inventory-service/default → inventory-service.yml│
└──┬──────────┬──────────┬──────────┬──────────────────────┘
   │          │          │          │
   │ GET      │ GET      │ GET      │ GET
   │ /product │ /order   │/inventory│ /api-gateway
   ▼          ▼          ▼          ▼
┌────────┐ ┌────────┐ ┌──────────┐ ┌──────────┐
│PRODUCT │ │ ORDER  │ │INVENTORY │ │API GW    │
│SERVICE │ │SERVICE │ │ SERVICE  │ │(:9000)   │
│(:8080) │ │(:8081) │ │ (:8082)  │ │          │
│        │ │        │ │          │ │          │
│@Refresh│ │@Value  │ │ @Value   │ │ OAuth2   │
│Scope   │ │order.  │ │ allow-   │ │ config   │
│mensaje │ │enabled │ │backorders│ │ rutas    │
└────────┘ └────────┘ └──────────┘ └──────────┘

    POST /actuator/refresh → recarga config sin reiniciar
```

---

## 7. Tabla: ¿Quien usa que?

| Servicio | Config Client | Tiene @RefreshScope | Feature Flags | Actuator /refresh |
|---|---|---|---|---|
| **Product Service** | Si | Si (ProductController) | `app.maintenance.message` | Si |
| **Order Service** | Si | No | `order.enabled` (boton de panico) | Si |
| **Inventory Service** | Si | No | `inventory.allow-backorders` (venta infinita) | Si |
| **API Gateway** | Si | No | No | No configurado |
| **Discovery Server** | No | No | No | No |
| **Notification Service** | No | No | No | No |
| **Config Server** | No (es el servidor) | No | No | No |

---

## 8. Archivos clave de esta seccion

| Archivo | Que hace |
|---|---|
| `config-server/pom.xml` | Dependencia `spring-cloud-config-server` |
| `config-server/.../ConfigServerApplication.java` | `@EnableConfigServer` — activa el servidor |
| `config-server/src/main/resources/application.yaml` | Apunta al repo Git privado de GitHub |
| `config-data/product-service.yml` | Config centralizada: MongoDB, mensaje, Actuator |
| `config-data/order-service.yml` | Config centralizada: PostgreSQL, JWT, flag `order.enabled` |
| `config-data/inventory-service.yml` | Config centralizada: MySQL, flag `allow-backorders` |
| `config-data/api-gateway.yml` | Config centralizada: OAuth2, rutas, TokenRelay |
| `product-service/src/main/resources/application.yaml` | Solo `name` + `config.import` (minimo) |
| `order-service/src/main/resources/application.yaml` | Solo `name` + `config.import` (minimo) |
| `inventory-service/src/main/resources/application.yaml` | Solo `name` + `config.import` (minimo) |
| `product-service/.../controller/ProductController.java` | `@RefreshScope` + `@Value` mensaje |
| `order-service/.../service/impl/OrderServiceImpl.java` | `@Value` flag `order.enabled` (boton de panico) |
| `inventory-service/.../service/impl/InventoryServiceImpl.java` | `@Value` flag `allow-backorders` (venta infinita) |
