# Sección 7: Seguridad con Keycloak

> Notas del curso de Microservicios — Seguridad Centralizada con OAuth2

---

## Objetivo de la sección

Pasar de un esquema donde cada microservicio gestiona sus propios usuarios y contraseñas, a una **seguridad centralizada** donde:

- **Keycloak** maneja toda la identidad (usuarios, contraseñas, roles)
- **El API Gateway** protege el perímetro (valida tokens, filtra por roles)
- **Los microservicios** implementan seguridad a nivel de datos (lógica de propietario)

---

## 1. Servidor de Identidad — Keycloak con Docker

### Concepto

Ya no guardamos usuarios ni contraseñas en nuestras bases de datos. Delegamos toda la gestión de identidad a un **Identity Provider (IdP)** externo: Keycloak.

### Implementación: `docker-compose.yml`

```yaml
# Base de datos EXCLUSIVA de Keycloak (separada de las BDs de negocio)
keycloak-db:
  image: postgres:16-alpine
  ports:
    - "5433:5432"              # Puerto 5433 para no chocar con order-db (5432)
  environment:
    POSTGRES_USER: keycloak
    POSTGRES_PASSWORD: password
    POSTGRES_DB: keycloak-db   # Aquí Keycloak guarda usuarios, realms, sesiones, etc.
```

- **Para qué:** Keycloak necesita su propia BD para almacenar datos internos de identidad. Es totalmente independiente de las BDs de negocio.

```yaml
# El servidor Keycloak
keycloak:
  image: quay.io/keycloak/keycloak:24.0.1
  command: start-dev                  # Modo desarrollo (sin HTTPS, arranque rápido)
  environment:
    KC_DB: postgres                   # Motor de BD
    KC_DB_URL: jdbc:postgresql://keycloak-db:5432/keycloak-db
    KC_DB_USERNAME: keycloak
    KC_DB_PASSWORD: password
    KEYCLOAK_ADMIN: admin             # Super admin de la consola web
    KEYCLOAK_ADMIN_PASSWORD: admin
  ports:
    - "8080:8080"                     # Consola web: http://localhost:8080
  depends_on:
    - keycloak-db                     # No arranca hasta que la BD esté lista
```

- **Para qué:** Al hacer `docker-compose up`, tienes un IdP profesional corriendo. En `http://localhost:8080` con admin/admin configuras realms, clients, usuarios y roles.

---

## 2. Configuración del Reino (Realm & Clients)

### Concepto

En Keycloak:
- **Realm** = un "espacio aislado" de seguridad (como un tenant). El nuestro se llama `ecommerce-realm`.
- **Client** = una aplicación que quiere autenticar usuarios. El nuestro se llama `api-gateway-client`.
- **Roles** = permisos como `ADMIN` y `USER`, asignados a usuarios dentro del realm.

Esta configuración se hizo **manualmente en la consola web de Keycloak**, pero el código se conecta a ella así:

### Implementación: `config-data/api-gateway.yml`

```yaml
spring:
  security:
    oauth2:
      client:
        provider:
          keycloak:
            # URL del realm — Spring descubre automáticamente los endpoints de Keycloak
            issuer-uri: http://localhost:8080/realms/ecommerce-realm

        registration:
          api-gateway-client:
            provider: keycloak
            client-id: api-gateway-client                        # Nombre del client en Keycloak
            client-secret: WC3I4KzNvKMSqOzVm29Q8abZebBa8etM     # Secreto generado por Keycloak
            authorization-grant-type: authorization_code          # Flujo OAuth2 estándar
            redirect-uri: "{baseUrl}/login/oauth2/code/keycloak"  # Redirect después del login
            scope: openid                                         # Pide info de identidad (JWT)
```

- **`issuer-uri`**: Spring se conecta a `.../.well-known/openid-configuration` y descubre automáticamente dónde pedir tokens, dónde validarlos, y las llaves públicas.
- **`client-secret`**: Es la "contraseña" de tu aplicación ante Keycloak. Solo clientes que se identifiquen correctamente reciben tokens.
- **`authorization_code`**: El flujo más seguro de OAuth2 — el usuario se autentica directamente en Keycloak, no a través de tu app.
- **`scope: openid`**: Pide un ID Token con claims del usuario (nombre, email, roles, etc.)

---

## 3. Blindando el Gateway (Resource Server)

### Concepto

El Gateway pasa de ser un simple "enrutador" a ser un **guardián**. Valida que cada petición traiga un JWT válido antes de dejarla pasar.

### 3.1 Dependencias: `api-gateway/pom.xml`

```xml
<!-- Capacidad de VALIDAR JWTs (Resource Server) -->
<artifactId>spring-boot-starter-oauth2-resource-server</artifactId>

<!-- Capacidad de actuar como CLIENTE OAuth2 (necesario para TokenRelay) -->
<artifactId>spring-boot-starter-oauth2-client</artifactId>
```

- `oauth2-resource-server` = "Soy un servidor que protege recursos, valido tokens JWT"
- `oauth2-client` = "También actúo como cliente OAuth2" (requisito para que funcione TokenRelay)

### 3.2 Configuración YAML: `config-data/api-gateway.yml`

```yaml
resourceserver:
  jwt:
    issuer-uri: http://localhost:8080/realms/ecommerce-realm
```

Con esta sola línea, Spring Boot automáticamente:
1. Se conecta al endpoint `.well-known/openid-configuration` de Keycloak
2. Descarga las **llaves públicas RSA** de Keycloak
3. En cada request con `Authorization: Bearer <token>`, verifica la firma del JWT
4. Si el token es inválido, expirado o alterado → **401 Unauthorized** automático

### 3.3 SecurityConfig: `api-gateway/.../config/SecurityConfig.java`

```java
@Configuration            // "Spring, esta clase tiene configuración"
@EnableWebFluxSecurity    // "Activa seguridad reactiva" (Gateway usa WebFlux, no MVC)
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity serverHttpSecurity){
        serverHttpSecurity
            // Desactiva CSRF — en APIs REST con JWT no se necesita (no usamos cookies de sesión)
            .csrf(ServerHttpSecurity.CsrfSpec::disable)

            .authorizeExchange(auth -> auth
                // Eureka es infraestructura interna, no necesita token
                .pathMatchers("/eureka/**").permitAll()

                // VER productos e inventario es público (cualquiera navega la tienda)
                .pathMatchers(HttpMethod.GET, "/api/v1/product/**").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/v1/inventory/**").permitAll()

                // MODIFICAR productos/inventario → solo ADMIN
                .pathMatchers("/api/v1/product/**").hasRole(Role.ADMIN.name())
                .pathMatchers("/api/v1/inventory/**").hasRole(Role.ADMIN.name())

                // CREAR pedido → solo USER
                .pathMatchers(HttpMethod.POST, "/api/v1/order").hasRole(Role.USER.name())

                // VER pedidos → ADMIN o USER (con lógica diferente en el servicio)
                .pathMatchers(HttpMethod.GET, "/api/v1/order/**")
                    .hasAnyRole(Role.ADMIN.name(), Role.USER.name())

                // BORRAR/MODIFICAR pedidos → solo ADMIN
                .pathMatchers(HttpMethod.DELETE, "/api/v1/order/**").hasRole(Role.ADMIN.name())
                .pathMatchers(HttpMethod.PUT, "/api/v1/order/**").hasRole(Role.ADMIN.name())

                // Todo lo demás → al menos autenticado
                .anyExchange().authenticated()
            )
            // Activa validación JWT con el convertidor personalizado de roles
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwtSpec -> jwtSpec
                    .jwtAuthenticationConverter(reactiveJwtAuthenticationConverterAdapter()))
            );
        return serverHttpSecurity.build();
    }
```

**Dato clave sobre el orden:** Spring evalúa las reglas de arriba hacia abajo. Por eso primero va `GET permitAll()` y después `hasRole(ADMIN)` para los demás métodos HTTP. Si se invierten, el `permitAll` nunca se ejecutaría.

### Enum de roles: `api-gateway/.../enums/Role.java`

```java
public enum Role {
    ADMIN,   // Role.ADMIN.name() → "ADMIN"
    USER     // Role.USER.name() → "USER"
}
```

Centraliza los nombres para evitar typos en los strings.

---

## 4. Traductor de Roles (JWT Converter)

### Concepto

**El problema:** Keycloak emite roles en una estructura JSON anidada dentro del JWT:
```json
{
  "realm_access": {
    "roles": ["ADMIN", "USER", "offline_access"]
  }
}
```

**Spring Security espera** las autoridades como lista plana con prefijo `ROLE_`: `ROLE_ADMIN`, `ROLE_USER`.

**La solución:** Un convertidor personalizado que traduce del formato Keycloak al formato Spring.

### Implementación: `SecurityConfig.java` (método privado)

```java
private ReactiveJwtAuthenticationConverterAdapter reactiveJwtAuthenticationConverterAdapter(){
    JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();

    // Le dice a Spring: "cuando tengas un JWT, extrae los roles ASÍ..."
    jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(jwt -> {

        // Saca el objeto "realm_access" del JWT
        Map<String, Object> realmAccess = jwt.getClaims().get("realm_access");

        // Si no tiene realm_access → no hay roles → lista vacía
        if(realmAccess == null || realmAccess.isEmpty()){
            return Collections.emptyList();
        }

        // Saca la lista "roles" → ["ADMIN", "USER", ...]
        Collection<String> roles = realmAccess.get("roles");

        // Convierte cada rol: "ADMIN" → SimpleGrantedAuthority("ROLE_ADMIN")
        return roles.stream()
                .map(roleName -> new SimpleGrantedAuthority("ROLE_" + roleName))
                .collect(Collectors.toList());
    });

    // Envuelve en adaptador reactivo (porque Gateway usa WebFlux)
    return new ReactiveJwtAuthenticationConverterAdapter(jwtAuthenticationConverter);
}
```

**Sin este convertidor**, cuando pones `.hasRole("ADMIN")` en las rutas, Spring no sabría dónde buscar el rol dentro del JWT de Keycloak. Este método es el **puente** entre el formato de Keycloak y el de Spring.

---

## 5. El Patrón Token Relay (Propagación)

### Concepto

**El problema:** El usuario se autentica en el Gateway, pero el pedido lo procesa el `order-service`. ¿Cómo sabe `order-service` quién es el usuario si la autenticación fue en el Gateway?

**La solución:** **Token Relay** — el Gateway "releva" (reenvía) el JWT hacia los microservicios internos.

### Implementación: `config-data/api-gateway.yml`

```yaml
cloud:
  gateway:
    default-filters:
      - TokenRelay    # ← ¡Una sola línea hace toda la magia!
```

Con esto, el Gateway automáticamente:
1. Toma el JWT del header `Authorization: Bearer <token>` del cliente
2. Lo **copia** al request que envía al microservicio destino
3. El microservicio recibe el mismo JWT como si el usuario le hubiera hablado directamente

**Sin `TokenRelay`**, el request llegaría al `order-service` sin token, y el servicio no sabría quién es el usuario.

**Requisito:** La dependencia `spring-boot-starter-oauth2-client` en el pom.xml del Gateway es necesaria para que TokenRelay funcione.

---

## 6. Configurando Rutas y Roles

### Rutas del Gateway: `api-gateway/.../config/GatewayConfig.java`

```java
@Bean
public RouteLocator routeLocator(RouteLocatorBuilder builder){
    return builder.routes()
        .route("product-service", r -> r
                .path("/api/v1/product/**")
                .uri("lb://PRODUCT-SERVICE"))       // lb:// = Load Balancer vía Eureka

        .route("order-service", r -> r
                .path("/api/v1/order/**")
                .uri("lb://ORDER-SERVICE"))

        .route("inventory-service", r -> r
                .path("/api/v1/inventory/**")
                .uri("lb://INVENTORY-SERVICE"))
        .build();
}
```

### Flujo completo de un request

1. Cliente hace `POST http://localhost:9000/api/v1/order` con `Authorization: Bearer <JWT>`
2. `SecurityWebFilterChain` intercepta → valida JWT → extrae roles con el converter
3. Verifica que el usuario tenga `ROLE_USER` (por la regla `hasRole("USER")`)
4. Si pasa → el filtro `TokenRelay` copia el JWT al request interno
5. `RouteLocator` envía al `lb://ORDER-SERVICE` (descubierto por Eureka)
6. `order-service` recibe el request **con el JWT incluido**

---

## 7. Validación de JWT en los Microservicios

### Concepto

**¿Quién valida el JWT en el order-service?** No escribimos ninguna clase de seguridad. Lo hace Spring **automáticamente** gracias a dos cosas:

### Dependencia: `order-service/pom.xml`

```xml
<artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
```

Activa un filtro automático llamado `BearerTokenAuthenticationFilter` que se inserta **antes** de que el request llegue al controller.

### Configuración: `config-data/order-service.yml`

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:8080/realms/ecommerce-realm
```

Al arrancar, Spring:
1. Se conecta al `.well-known/openid-configuration` de Keycloak
2. Descarga las llaves públicas RSA
3. Las guarda en memoria

### ¿Qué pasa en CADA request?

```
Request llega con header: Authorization: Bearer eyJhbGci...
                │
                ▼
┌──────────────────────────────────────┐
│  BearerTokenAuthenticationFilter     │  ← Filtro AUTOMÁTICO (no escribimos código)
│                                      │
│  1. Extrae el token del header       │
│  2. Decodifica el JWT (Base64)       │
│  3. Verifica la FIRMA con llave      │
│     pública de Keycloak              │
│  4. Verifica que NO esté expirado    │
│  5. Verifica que el "iss" sea el     │
│     realm correcto                   │
│                                      │
│  ¿OK? → Crea objeto Jwt             │
│  ¿Falla? → 401 (nunca llega al      │
│            controller)               │
└──────────────────────────────────────┘
                │
                ▼  (solo si pasó)
┌──────────────────────────────────────┐
│  OrderController                     │
│  @AuthenticationPrincipal Jwt jwt    │
│  → Spring inyecta el JWT validado    │
└──────────────────────────────────────┘
```

### Doble validación Gateway vs Microservicio

| | **Gateway** | **order-service** |
|---|---|---|
| Valida JWT (firma, expiración) | Sí | Sí |
| Revisa roles en rutas | Sí (`hasRole`) | No (no tiene SecurityConfig) |
| Extrae datos del JWT | Solo para decidir acceso | Sí, para lógica de negocio |

El order-service valida de nuevo como **defensa en profundidad**: si alguien llama directamente al `order-service:8081` saltándose el Gateway, el servicio rechaza requests sin token válido.

---

## 8. Historial de Pedidos — Lógica de Propietario (Seguridad a Nivel de Datos)

### Concepto

No basta con proteger la URL. Si un USER puede hacer GET a `/api/v1/order`, ¿verá los pedidos de TODOS? No. Cada usuario ve **solo sus propios pedidos**, y el ADMIN ve todos.

### Paso 1: Guardar quién hizo el pedido

**Modelo `Order.java`:**
```java
private String userId;   // Campo en la entidad JPA / tabla de BD
```

**`OrderController.java` — POST:**
```java
@PostMapping
public OrderResponse placeOrder(@Valid @RequestBody OrderRequest orderRequest,
                                @AuthenticationPrincipal Jwt jwt){
    // jwt.getSubject() = el claim "sub" del JWT = ID único del usuario en Keycloak
    // Ejemplo: "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
    return orderService.placeOrder(orderRequest, jwt.getSubject());
}
```

**`OrderServiceImpl.java`:**
```java
Order order = orderMapper.toOrder(orderRequest);
order.setUserId(userId);   // ← Graba el ID de Keycloak en la orden
```

### Paso 2: Filtrar según quién consulta

**`OrderController.java` — GET:**
```java
@GetMapping
public List<OrderResponse> getOrders(@AuthenticationPrincipal Jwt jwt){
    String userId = jwt.getSubject();          // ¿Quién está preguntando?

    boolean isAdmin = false;

    // Lee los roles directamente del JWT (misma estructura que el converter)
    Map<String, Object> realmAcces = jwt.getClaim("realm_access");
    if(realmAcces != null && realmAcces.containsKey("roles")){
        List<String> roles = (List<String>) realmAcces.get("roles");
        isAdmin = roles.stream().anyMatch(role -> role.equalsIgnoreCase("ADMIN"));
    }

    return orderService.getOrders(userId, isAdmin);
}
```

**`OrderServiceImpl.java`:**
```java
public List<OrderResponse> getOrders(String userId, boolean isAdmin) {
    List<Order> orders;
    if(isAdmin){
        orders = orderRepository.findAll();              // ADMIN → TODAS las órdenes
    } else {
        orders = orderRepository.findByUserId(userId);   // USER → solo SUS órdenes
    }
    return orders.stream().map(orderMapper::toOrderResponse).toList();
}
```

### Alternativa más limpia: `@PreAuthorize`

En lugar de parsear manualmente los roles en el controller, se podría usar `@EnableMethodSecurity` + `@PreAuthorize`:

```java
@DeleteMapping("/{id}")
@PreAuthorize("hasRole('ADMIN')")
public void deleteOrder(@PathVariable Long id){ ... }
```

Pero para eso necesitarías también el JWT Converter en el order-service para que Spring entienda los roles de Keycloak.

---

## 9. Resumen Visual — Flujo Completo de Seguridad

```
Usuario               Keycloak                  Gateway (:9000)           order-service (:8081)
  │                      │                           │                          │
  │── Login ────────────>│                           │                          │
  │<── JWT (token) ──────│                           │                          │
  │                      │                           │                          │
  │── POST /api/v1/order ──────────────────────────> │                          │
  │   + Bearer <JWT>     │                           │                          │
  │                      │   1. Valida firma JWT     │                          │
  │                      │   2. JWT Converter:       │                          │
  │                      │      realm_access →       │                          │
  │                      │      ROLE_USER            │                          │
  │                      │   3. ¿hasRole(USER)? ✅   │                          │
  │                      │   4. TokenRelay: copia JWT│                          │
  │                      │                           │── POST + Bearer JWT ────>│
  │                      │                           │                          │
  │                      │                           │    5. Valida JWT (firma) │
  │                      │                           │    6. jwt.getSubject()   │
  │                      │                           │       → userId           │
  │                      │                           │    7. order.setUserId()  │
  │                      │                           │    8. Guarda en BD       │
  │<── 201 Created ──────│───────────────────────────│<── OrderResponse ────────│
```

---

## 10. Arquitectura de Seguridad por Servicio

| Servicio | ¿Valida JWT? | ¿Filtra por roles? | ¿Lógica de propietario? | Dependencia OAuth2 |
|---|---|---|---|---|
| **API Gateway** | Sí | Sí (SecurityConfig) | No | resource-server + client |
| **order-service** | Sí | No (confía en Gateway) | Sí (userId del JWT) | resource-server |
| **product-service** | No | No | No | Ninguna |
| **inventory-service** | No | No | No | Ninguna |
| **notification-service** | No | No | No | Ninguna |

**Principio:** El Gateway protege el perímetro, los microservicios que necesitan saber "quién" validan el JWT por su cuenta (defensa en profundidad).

---

## Archivos clave de esta sección

| Archivo | Qué hace |
|---|---|
| `docker-compose.yml` | Levanta Keycloak + su BD con Docker |
| `api-gateway/pom.xml` | Dependencias: oauth2-resource-server + oauth2-client |
| `config-data/api-gateway.yml` | Config OAuth2 client + resource server + TokenRelay |
| `api-gateway/.../config/SecurityConfig.java` | Reglas de acceso por ruta/rol + JWT Converter |
| `api-gateway/.../enums/Role.java` | Enum con ADMIN y USER |
| `api-gateway/.../config/GatewayConfig.java` | Rutas del Gateway hacia microservicios |
| `order-service/pom.xml` | Dependencia: oauth2-resource-server |
| `config-data/order-service.yml` | Config resource server JWT para validación |
| `order-service/.../controller/OrderController.java` | Extrae userId y roles del JWT |
| `order-service/.../service/impl/OrderServiceImpl.java` | Lógica de propietario (mis pedidos vs todos) |
| `order-service/.../model/Order.java` | Campo `userId` en la entidad |
