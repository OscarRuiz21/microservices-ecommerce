# Guía: De Docker Compose a Kubernetes en Producción

**Contexto:** Este proyecto es un sistema de ecommerce con microservicios Spring Boot. En desarrollo local usábamos Docker Compose, Eureka Server para el descubrimiento de servicios y Spring Cloud Config Server para centralizar la configuración desde un repositorio privado de GitHub. Esta guía documenta el proceso completo para llevar ese proyecto a Kubernetes (Minikube), los problemas encontrados y cómo resolverlos.

---

## Arquitectura del Proyecto

### Microservicios

| Servicio | Puerto | Base de Datos |
|---|---|---|
| api-gateway | 9000 | — |
| product-service | 8081 | MongoDB |
| order-service | 8082 | PostgreSQL |
| inventory-service | 8083 | MySQL |
| notification-service | 8084 | — |

### Infraestructura
- **RabbitMQ** — mensajería asíncrona entre servicios
- **Keycloak** — autenticación OAuth2/JWT
- **Grafana LGTM** — observabilidad (logs, métricas, trazas)

---

## El Gran Cambio: De Eureka + Config Server a Kubernetes

Este es el concepto central de toda la migración. En desarrollo local:

```
Microservicio A → Eureka Server → "¿Dónde está order-service?" → lb://ORDER-SERVICE
Microservicio A → Config Server → "Dame mi configuración" → GitHub repo privado
```

En Kubernetes esto ya no es necesario porque K8s lo resuelve nativamente:

```
Microservicio A → DNS de K8s → "order-service" → Pod del order-service
Microservicio A → Variables de entorno / application-k8s.yml → Configuración directa
```

### ¿Por qué Kubernetes reemplaza a Eureka?
Cuando creás un Service en K8s con nombre `order-service`, el DNS interno del cluster resuelve automáticamente ese nombre al pod correcto. No hace falta un servidor de descubrimiento externo.

```java
// Antes (con Eureka)
.uri("lb://ORDER-SERVICE")  // Necesita que Eureka esté corriendo

// Después (con K8s DNS)
.uri("http://order-service:8082")  // K8s resuelve esto nativamente
```

### ¿Por qué Kubernetes reemplaza a Config Server?
En K8s podés pasar configuración a cada pod mediante variables de entorno en el Deployment YAML, o mediante perfiles de Spring Boot (`application-k8s.yml`). Ya no necesitás un servidor centralizado que se conecte a GitHub.

---

## Estructura de Archivos K8s

```
k8s/
├── infrastructure-pvcs.yaml      # Volúmenes persistentes
├── databases.yaml                # MongoDB, MySQL, PostgreSQL (para Keycloak y Order)
├── rabbitmq-pvc.yaml
├── rabbitmq-deployment.yaml
├── rabbitmq-service.yaml
├── security-observability.yaml   # Keycloak + Grafana LGTM
├── gateway-deployment.yaml
├── gateway-service.yaml
├── product-deployment.yaml
├── order-deployment.yaml
├── inventory-deployment.yaml
└── notification-deployment.yaml
```

---

## Cambios en el Código Fuente

### 1. GatewayConfig.java — Cambiar URIs de Eureka a DNS de K8s

```java
// ANTES (desarrollo local con Eureka)
.route("order-service", r -> r
    .path("/api/v1/order/**")
    .uri("lb://ORDER-SERVICE"))  // ❌ Necesita Eureka

// DESPUÉS (Kubernetes)
.route("order-service", r -> r
    .path("/api/v1/order/**")
    .uri("http://order-service:8082"))  // ✅ DNS de K8s
```

El archivo final completo:

```java
@Configuration
public class GatewayConfig {

    @Bean
    public RouteLocator routeLocator(RouteLocatorBuilder builder){
        return builder.routes()
                .route("product-service", r -> r
                        .path("/api/v1/product/**")
                        .uri("http://product-service:8081"))
                .route("order-service", r -> r
                        .path("/api/v1/order/**")
                        .uri("http://order-service:8082"))
                .route("inventory-service", r -> r
                        .path("/api/v1/inventory/**")
                        .uri("http://inventory-service:8083"))
                .build();
    }
}
```

**Importante:** Las rutas definidas en código Java tienen prioridad sobre las definidas en YML. Si tenés rutas en ambos lados, las del código ganan.

### 2. application-k8s.yml — Perfil específico para Kubernetes

Crear el archivo `src/main/resources/application-k8s.yml` en el api-gateway:

```yaml
spring:
  cloud:
    config:
      enabled: false
    discovery:
      enabled: false
    gateway:
      routes:
        - id: product-service
          uri: http://product-service:8081
          predicates:
            - Path=/api/v1/product/**
        - id: order-service
          uri: http://order-service:8082
          predicates:
            - Path=/api/v1/order/**
        - id: inventory-service
          uri: http://inventory-service:8083
          predicates:
            - Path=/api/v1/inventory/**
  rabbitmq:
    host: rabbitmq

security:
  oauth2:
    resourceserver:
      jwt:
        issuer-uri: http://keycloak:8080/realms/ecommerce-realm
```

Este perfil se activa cuando el pod corre con `SPRING_PROFILES_ACTIVE=k8s`.

---

## Archivos YAML de Kubernetes

### infrastructure-pvcs.yaml

```yaml
apiVersion: v1
kind: List
items:
  - apiVersion: v1
    kind: PersistentVolumeClaim
    metadata:
      name: mongo-pvc
    spec:
      accessModes: [ReadWriteOnce]
      resources: { requests: { storage: 1Gi } }
  - apiVersion: v1
    kind: PersistentVolumeClaim
    metadata:
      name: inventory-db-pvc
    spec:
      accessModes: [ReadWriteOnce]
      resources: { requests: { storage: 1Gi } }
  - apiVersion: v1
    kind: PersistentVolumeClaim
    metadata:
      name: order-db-pvc
    spec:
      accessModes: [ReadWriteOnce]
      resources: { requests: { storage: 1Gi } }
  - apiVersion: v1
    kind: PersistentVolumeClaim
    metadata:
      name: keycloak-db-pvc
    spec:
      accessModes: [ReadWriteOnce]
      resources: { requests: { storage: 1Gi } }
```

### rabbitmq-pvc.yaml

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: rabbitmq-pvc
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 1Gi
```

### databases.yaml

```yaml
# 1. MONGODB (Product Service)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mongodb
spec:
  selector:
    matchLabels:
      app: mongodb
  template:
    metadata:
      labels:
        app: mongodb
    spec:
      containers:
        - name: mongodb
          image: mongo:7.0.4
          ports:
            - containerPort: 27017
          env:
            - name: MONGO_INITDB_ROOT_USERNAME
              value: "root"
            - name: MONGO_INITDB_ROOT_PASSWORD
              value: "password"
          volumeMounts:
            - name: mongo-storage
              mountPath: /data/db
      volumes:
        - name: mongo-storage
          persistentVolumeClaim:
            claimName: mongo-pvc
---
apiVersion: v1
kind: Service
metadata:
  name: mongodb
spec:
  selector:
    app: mongodb
  ports:
    - port: 27017
      targetPort: 27017
---
# 2. MYSQL (Inventory Service)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: inventory-db
spec:
  selector:
    matchLabels:
      app: inventory-db
  template:
    metadata:
      labels:
        app: inventory-db
    spec:
      containers:
        - name: mysql
          image: mysql:8
          ports:
            - containerPort: 3306
          env:
            - name: MYSQL_ROOT_PASSWORD
              value: "root"
            - name: MYSQL_DATABASE
              value: "inventory-db"
          volumeMounts:
            - name: mysql-storage
              mountPath: /var/lib/mysql
      volumes:
        - name: mysql-storage
          persistentVolumeClaim:
            claimName: inventory-db-pvc
---
apiVersion: v1
kind: Service
metadata:
  name: inventory-db
spec:
  selector:
    app: inventory-db
  ports:
    - port: 3306
      targetPort: 3306
---
# 3. POSTGRES (Order Service)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order-db
spec:
  selector:
    matchLabels:
      app: order-db
  template:
    metadata:
      labels:
        app: order-db
    spec:
      containers:
        - name: postgres
          image: postgres:16-alpine
          ports:
            - containerPort: 5432
          env:
            - name: POSTGRES_USER
              value: "admin"
            - name: POSTGRES_PASSWORD
              value: "admin"
            - name: POSTGRES_DB
              value: "order-db"
          volumeMounts:
            - name: order-storage
              mountPath: /var/lib/postgresql/data
      volumes:
        - name: order-storage
          persistentVolumeClaim:
            claimName: order-db-pvc
---
apiVersion: v1
kind: Service
metadata:
  name: order-db
spec:
  selector:
    app: order-db
  ports:
    - port: 5432
      targetPort: 5432
---
# 4. POSTGRES (Keycloak DB)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: keycloak-db
spec:
  selector:
    matchLabels:
      app: keycloak-db
  template:
    metadata:
      labels:
        app: keycloak-db
    spec:
      containers:
        - name: postgres
          image: postgres:16-alpine
          ports:
            - containerPort: 5432
          env:
            - name: POSTGRES_USER
              value: "keycloak"
            - name: POSTGRES_PASSWORD
              value: "password"
            - name: POSTGRES_DB
              value: "keycloak-db"
          volumeMounts:
            - name: keycloak-db-storage
              mountPath: /var/lib/postgresql/data
      volumes:
        - name: keycloak-db-storage
          persistentVolumeClaim:
            claimName: keycloak-db-pvc
---
apiVersion: v1
kind: Service
metadata:
  name: keycloak-db
spec:
  selector:
    app: keycloak-db
  ports:
    - port: 5432
      targetPort: 5432
```

### rabbitmq-deployment.yaml

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: rabbitmq-deployment
spec:
  replicas: 1
  selector:
    matchLabels:
      app: rabbitmq
  template:
    metadata:
      labels:
        app: rabbitmq
    spec:
      containers:
        - name: rabbitmq
          image: rabbitmq:4.2-management
          ports:
            - containerPort: 5672
            - containerPort: 15672
          env:
            - name: RABBITMQ_DEFAULT_USER
              value: "guest"
            - name: RABBITMQ_DEFAULT_PASS
              value: "guest"
          volumeMounts:
            - name: rabbitmq-data
              mountPath: /var/lib/rabbitmq
      volumes:
        - name: rabbitmq-data
          persistentVolumeClaim:
            claimName: rabbitmq-pvc
```

### rabbitmq-service.yaml

```yaml
apiVersion: v1
kind: Service
metadata:
  name: rabbitmq
spec:
  type: NodePort
  selector:
    app: rabbitmq
  ports:
    - name: amqp
      port: 5672
      targetPort: 5672
    - name: http
      port: 15672
      targetPort: 15672
      nodePort: 31672
```

### security-observability.yaml

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: keycloak
spec:
  selector:
    matchLabels:
      app: keycloak
  template:
    metadata:
      labels:
        app: keycloak
    spec:
      containers:
        - name: keycloak
          image: quay.io/keycloak/keycloak:24.0.1
          command: ["/opt/keycloak/bin/kc.sh", "start-dev", "--import-realm"]
          env:
            - name: KC_DB
              value: "postgres"
            - name: KC_DB_URL
              value: "jdbc:postgresql://keycloak-db:5432/keycloak-db"
            - name: KC_DB_USERNAME
              value: "keycloak"
            - name: KC_DB_PASSWORD
              value: "password"
            - name: KEYCLOAK_ADMIN
              value: "admin"
            - name: KEYCLOAK_ADMIN_PASSWORD
              value: "admin"
            - name: KC_HOSTNAME
              value: "keycloak"
            - name: KC_HOSTNAME_PORT
              value: "8080"
            - name: KC_HOSTNAME_STRICT
              value: "false"
            - name: KC_HOSTNAME_STRICT_BACKCHANNEL
              value: "false"
          volumeMounts:
            - name: realm-config
              mountPath: /opt/keycloak/data/import/ecommerce-realm.json
              subPath: ecommerce-realm.json
      volumes:
        - name: realm-config
          configMap:
            name: keycloak-realm-config
---
apiVersion: v1
kind: Service
metadata:
  name: keycloak
spec:
  type: NodePort
  selector:
    app: keycloak
  ports:
    - port: 8080
      targetPort: 8080
      nodePort: 31080
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: lgtm
spec:
  replicas: 1
  selector:
    matchLabels:
      app: lgtm
  template:
    metadata:
      labels:
        app: lgtm
    spec:
      containers:
        - name: lgtm
          image: grafana/otel-lgtm:latest
          ports:
            - containerPort: 3000
            - containerPort: 4317
            - containerPort: 4318
          env:
            - name: GF_SECURITY_ADMIN_PASSWORD
              value: "admin"
---
apiVersion: v1
kind: Service
metadata:
  name: lgtm
spec:
  type: NodePort
  selector:
    app: lgtm
  ports:
    - name: grafana
      port: 3000
      targetPort: 3000
      nodePort: 31300
    - name: otel-grpc
      port: 4317
      targetPort: 4317
    - name: otel-http
      port: 4318
      targetPort: 4318
```

### gateway-deployment.yaml

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: gateway-deployment
  labels:
    app: api-gateway
spec:
  replicas: 1
  selector:
    matchLabels:
      app: api-gateway
  template:
    metadata:
      labels:
        app: api-gateway
    spec:
      containers:
        - name: api-gateway
          image: api-gateway:latest
          imagePullPolicy: Never
          ports:
            - containerPort: 9000
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "k8s"
            - name: SERVER_PORT
              value: "9000"
            - name: SPRING_CLOUD_CONFIG_ENABLED
              value: "false"
            - name: EUREKA_CLIENT_ENABLED
              value: "false"
            - name: SPRING_CLOUD_DISCOVERY_ENABLED
              value: "false"
            - name: MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT
              value: "http://lgtm:4318/v1/traces"
            - name: MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT
              value: "http://lgtm:4318/v1/logs"
            - name: MANAGEMENT_OTLP_METRICS_EXPORT_URL
              value: "http://lgtm:4318/v1/metrics"
            - name: MANAGEMENT_TRACING_SAMPLING_PROBABILITY
              value: "1.0"
            - name: SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI
              value: "http://keycloak:8080/realms/ecommerce-realm"
```

### gateway-service.yaml

```yaml
apiVersion: v1
kind: Service
metadata:
  name: gateway-service
spec:
  type: NodePort
  selector:
    app: api-gateway
  ports:
    - port: 9000
      targetPort: 9000
      nodePort: 30000
```

### product-deployment.yaml

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: product-deployment
spec:
  replicas: 1
  selector:
    matchLabels:
      app: product-service
  template:
    metadata:
      labels:
        app: product-service
    spec:
      containers:
        - name: product-service
          image: product-service:latest
          imagePullPolicy: Never
          ports:
            - containerPort: 8081
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "k8s"
            - name: SERVER_PORT
              value: "8081"
            - name: SPRING_CLOUD_CONFIG_ENABLED
              value: "false"
            - name: EUREKA_CLIENT_ENABLED
              value: "false"
            - name: SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI
              value: "http://keycloak:8080/realms/ecommerce-realm"
            - name: SPRING_DATA_MONGODB_HOST
              value: "mongodb"
            - name: SPRING_DATA_MONGODB_PORT
              value: "27017"
            - name: SPRING_DATA_MONGODB_DATABASE
              value: "product-db"
            - name: SPRING_DATA_MONGODB_USERNAME
              value: "root"
            - name: SPRING_DATA_MONGODB_PASSWORD
              value: "password"
            - name: SPRING_DATA_MONGODB_AUTHENTICATION_DATABASE
              value: "admin"
---
apiVersion: v1
kind: Service
metadata:
  name: product-service
spec:
  selector:
    app: product-service
  ports:
    - port: 8081
      targetPort: 8081
```

### order-deployment.yaml

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order-deployment
spec:
  replicas: 1
  selector:
    matchLabels:
      app: order-service
  template:
    metadata:
      labels:
        app: order-service
    spec:
      containers:
        - name: order-service
          image: order-service:latest
          imagePullPolicy: Never
          ports:
            - containerPort: 8082
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "k8s"
            - name: SERVER_PORT
              value: "8082"
            - name: SPRING_CLOUD_CONFIG_ENABLED
              value: "false"
            - name: EUREKA_CLIENT_ENABLED
              value: "false"
            - name: SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI
              value: "http://keycloak:8080/realms/ecommerce-realm"
            - name: MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT
              value: "http://lgtm:4318/v1/traces"
            - name: MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT
              value: "http://lgtm:4318/v1/logs"
            - name: MANAGEMENT_OTLP_METRICS_EXPORT_URL
              value: "http://lgtm:4318/v1/metrics"
            - name: MANAGEMENT_TRACING_SAMPLING_PROBABILITY
              value: "1.0"
            - name: SPRING_DATASOURCE_URL
              value: "jdbc:postgresql://order-db:5432/order-db"
            - name: SPRING_DATASOURCE_USERNAME
              value: "admin"
            - name: SPRING_DATASOURCE_PASSWORD
              value: "admin"
            - name: SPRING_RABBITMQ_HOST
              value: "rabbitmq"
            - name: SPRING_JPA_HIBERNATE_DDL_AUTO
              value: "update"
---
apiVersion: v1
kind: Service
metadata:
  name: order-service
spec:
  selector:
    app: order-service
  ports:
    - port: 8082
      targetPort: 8082
```

### inventory-deployment.yaml

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: inventory-deployment
spec:
  replicas: 1
  selector:
    matchLabels:
      app: inventory-service
  template:
    metadata:
      labels:
        app: inventory-service
    spec:
      containers:
        - name: inventory-service
          image: inventory-service:latest
          imagePullPolicy: Never
          ports:
            - containerPort: 8083
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "k8s"
            - name: SERVER_PORT
              value: "8083"
            - name: SPRING_CLOUD_CONFIG_ENABLED
              value: "false"
            - name: EUREKA_CLIENT_ENABLED
              value: "false"
            - name: SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI
              value: "http://keycloak:8080/realms/ecommerce-realm"
            - name: MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT
              value: "http://lgtm:4318/v1/traces"
            - name: MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT
              value: "http://lgtm:4318/v1/logs"
            - name: MANAGEMENT_OTLP_METRICS_EXPORT_URL
              value: "http://lgtm:4318/v1/metrics"
            - name: MANAGEMENT_TRACING_SAMPLING_PROBABILITY
              value: "1.0"
            - name: SPRING_DATASOURCE_URL
              value: "jdbc:mysql://inventory-db:3306/inventory-db"
            - name: SPRING_DATASOURCE_USERNAME
              value: "root"
            - name: SPRING_DATASOURCE_PASSWORD
              value: "root"
            - name: SPRING_RABBITMQ_HOST
              value: "rabbitmq"
            - name: SPRING_JPA_HIBERNATE_DDL_AUTO
              value: "update"
---
apiVersion: v1
kind: Service
metadata:
  name: inventory-service
spec:
  selector:
    app: inventory-service
  ports:
    - port: 8083
      targetPort: 8083
```

### notification-deployment.yaml

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: notification-deployment
spec:
  replicas: 1
  selector:
    matchLabels:
      app: notification-service
  template:
    metadata:
      labels:
        app: notification-service
    spec:
      containers:
        - name: notification-service
          image: notification-service:latest
          imagePullPolicy: Never
          ports:
            - containerPort: 8084
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "k8s"
            - name: SERVER_PORT
              value: "8084"
            - name: SPRING_CLOUD_CONFIG_ENABLED
              value: "false"
            - name: EUREKA_CLIENT_ENABLED
              value: "false"
            - name: SPRING_RABBITMQ_HOST
              value: "rabbitmq"
            - name: SPRING_MAIL_HOST
              value: "sandbox.smtp.mailtrap.io"
            - name: SPRING_MAIL_PORT
              value: "2525"
            - name: SPRING_MAIL_USERNAME
              value: "TU_USERNAME_MAILTRAP"
            - name: SPRING_MAIL_PASSWORD
              value: "TU_PASSWORD_MAILTRAP"
---
apiVersion: v1
kind: Service
metadata:
  name: notification-service
spec:
  selector:
    app: notification-service
  ports:
    - port: 8084
      targetPort: 8084
```

---

## Proceso de Deploy: Ciclo Completo

### Prerrequisitos
- Docker Desktop instalado y corriendo
- Minikube instalado (`brew install minikube`)
- kubectl instalado

### Paso 1: Iniciar Minikube

```bash
minikube start --driver=docker --cpus=4 --memory=6144
```

### Paso 2: Exportar el realm de Keycloak

Antes de levantar Keycloak en Kubernetes, exportá el realm desde tu instalación local:

1. Levantar Keycloak local: `docker-compose up -d keycloak keycloak-db`
2. Entrar a `http://localhost:8080` con `admin` / `admin`
3. Seleccionar el realm `ecommerce-realm`
4. Ir a **Realm settings** → **Action** → **Export**
5. Activar **Export groups and roles** y **Export clients** → **Export**
6. Guardar el `ecommerce-realm.json` en la raíz del proyecto
7. Bajar Docker Compose: `docker-compose down`

### Paso 3: Agregar keycloak al archivo hosts

Este paso es necesario para que el navegador pueda resolver el hostname `keycloak` y la UI de Keycloak cargue correctamente.

**Mac y Linux:**
```bash
echo "127.0.0.1 keycloak" | sudo tee -a /etc/hosts
```

**Windows:**
Abrí el Bloc de notas **como administrador** y editá el archivo:
```
C:\Windows\System32\drivers\etc\hosts
```
Agregá al final:
```
127.0.0.1 keycloak
```
Guardá el archivo y cerrá.

### Paso 4: Crear el ConfigMap del realm

```bash
kubectl create configmap keycloak-realm-config \
  --from-file=ecommerce-realm.json
```

### Paso 5: Aplicar toda la infraestructura y microservicios

```bash
kubectl apply -f k8s/
```

### Paso 6: Construir e inyectar las imágenes de los microservicios

**Concepto clave:** Minikube corre en su propio contenedor separado de Docker Desktop. Las imágenes que construís con `docker build` no son visibles automáticamente para Minikube. Hay que inyectarlas explícitamente con `minikube image load`.

```bash
cd api-gateway && ./mvnw clean package -DskipTests && docker build -t api-gateway:latest . && minikube image load api-gateway:latest && cd ..
cd order-service && ./mvnw clean package -DskipTests && docker build -t order-service:latest . && minikube image load order-service:latest && cd ..
cd product-service && ./mvnw clean package -DskipTests && docker build -t product-service:latest . && minikube image load product-service:latest && cd ..
cd inventory-service && ./mvnw clean package -DskipTests && docker build -t inventory-service:latest . && minikube image load inventory-service:latest && cd ..
cd notification-service && ./mvnw clean package -DskipTests && docker build -t notification-service:latest . && minikube image load notification-service:latest && cd ..
```

### Paso 7: Verificar que todo está corriendo

```bash
kubectl get pods
```

Todos deben estar en estado `Running`.

### Paso 8: Crear usuarios en Keycloak

Abrir port-forward de Keycloak:

```bash
kubectl port-forward svc/keycloak 8080:8080
```

Entrar a `http://keycloak:8080` con `admin` / `admin` y crear los usuarios.

**⚠️ Puntos críticos al crear usuarios:**
- En `Required user actions` — asegurarse de que **NO** tenga `Update Password` seleccionado
- En `Credentials` → `Reset password` → poner `Temporary: OFF`
- El usuario que va a hacer POST a `/api/v1/order` necesita el rol `USER`, no `ADMIN`

### Paso 9: Activar observabilidad con Grafana LGTM

Grafana LGTM ya está definido en el `security-observability.yaml`. Para activar la telemetría en los microservicios que ya tienen OpenTelemetry configurado, solo necesitás hacer un rollout restart — las variables de entorno correctas ya están en los YAMLs:

```bash
kubectl rollout restart deployment/gateway-deployment
kubectl rollout restart deployment/order-deployment
kubectl rollout restart deployment/inventory-deployment
```

Abrir port-forward de Grafana en una terminal nueva:

```bash
kubectl port-forward svc/lgtm 3000:3000
```

Acceder a `http://localhost:3000` con `admin` / `admin`.

---

## Acceso al Cluster según Sistema Operativo

### 🍎 Mac — Port Forwarding obligatorio

En Mac, Minikube corre dentro de un contenedor Docker que agrega una capa de aislamiento. Los NodePorts **no son accesibles directamente** desde el navegador ni desde Postman. Es necesario abrir un port-forward por cada servicio al que querés acceder.

Abrí tres terminales y dejalas corriendo:

```bash
# Terminal 1: Acceso a Keycloak
kubectl port-forward svc/keycloak 8080:8080

# Terminal 2: Acceso al Gateway
kubectl port-forward svc/gateway-service 30000:9000

# Terminal 3: Acceso a Grafana
kubectl port-forward svc/lgtm 3000:3000
```

**Importante:** Si reiniciás un pod (por ejemplo con `kubectl rollout restart`), el port-forward se corta y hay que volver a abrirlo.

---

### 🪟 Windows — Port Forwarding igual que Mac

En Windows, Minikube también corre dentro de un contenedor Docker, por lo que aplica la misma limitación que en Mac. El port-forward funciona exactamente igual:

```bash
# Terminal 1: Acceso a Keycloak
kubectl port-forward svc/keycloak 8080:8080

# Terminal 2: Acceso al Gateway
kubectl port-forward svc/gateway-service 30000:9000

# Terminal 3: Acceso a Grafana
kubectl port-forward svc/lgtm 3000:3000
```

Como alternativa podés usar `minikube service --url` para obtener una URL de acceso temporal, pero el port-forward es la opción más estable y consistente.

---

### 🐧 Linux — Acceso directo por NodePort

En Linux, Minikube corre de forma nativa sin capa de virtualización adicional. Los NodePorts son accesibles directamente desde `localhost`:

```bash
# Gateway
http://localhost:30000

# Keycloak
http://localhost:31080

# Grafana
http://localhost:31300

# RabbitMQ Management
http://localhost:31672
```

No necesitás port-forward. Los servicios están disponibles directamente desde el navegador y desde Postman.

---

## Configuración de Postman

### Obtener Token

| Campo | Valor |
|---|---|
| Type | OAuth 2.0 |
| Grant Type | Password Credentials |
| Access Token URL | `http://keycloak:8080/realms/ecommerce-realm/protocol/openid-connect/token` |
| Client ID | `api-gateway-client` |
| Client Secret | (Keycloak → Clients → api-gateway-client → Credentials) |
| Username | tu usuario |
| Password | tu contraseña |

### Endpoints disponibles

```
GET  http://localhost:30000/api/v1/product
POST http://localhost:30000/api/v1/product      (requiere rol ADMIN)
GET  http://localhost:30000/api/v1/inventory
POST http://localhost:30000/api/v1/inventory    (requiere rol ADMIN)
GET  http://localhost:30000/api/v1/order        (requiere rol ADMIN o USER)
POST http://localhost:30000/api/v1/order        (requiere rol USER)
```

---

## Problemas Encontrados y Soluciones

### ❌ Problema 1: ReactiveJwtDecoder bean not found al iniciar el Gateway

**Síntoma:** El gateway no arranca, falla con error de bean.

**Causa:** `application.yaml` tenía `spring.config.import: configserver:http://localhost:8888`. Spring intentaba conectarse al Config Server durante el bootstrap.

**Solución:**
```yaml
- name: SPRING_CLOUD_CONFIG_ENABLED
  value: "false"
- name: EUREKA_CLIENT_ENABLED
  value: "false"
- name: SPRING_CLOUD_DISCOVERY_ENABLED
  value: "false"
```

---

### ❌ Problema 2: Gateway arranca pero devuelve 503 en todos los endpoints

**Causa raíz 1:** Las rutas estaban en el Config Server con `lb://ORDER-SERVICE`. Al deshabilitar el Config Server, las rutas desaparecieron.

**Solución:** Mover las rutas al `GatewayConfig.java` con URIs de DNS de K8s.

**Causa raíz 2:** Minikube cachea las imágenes con el tag `latest`. Usar tags versionados para forzar la actualización:

```bash
docker build -t api-gateway:v2 .
minikube image load api-gateway:v2
kubectl delete deployment gateway-deployment
kubectl apply -f k8s/gateway-deployment.yaml
```

---

### ❌ Problema 3: Token JWT inválido — Issuer no coincide

**Causa:** El token tenía `iss: http://127.0.0.1:RANDOM_PORT` pero el gateway validaba contra `http://keycloak:8080`.

**Solución:** Configurar Keycloak con hostname fijo y agregar `keycloak` al `/etc/hosts`:

```yaml
- name: KC_HOSTNAME
  value: "keycloak"
- name: KC_HOSTNAME_PORT
  value: "8080"
```

```bash
echo "127.0.0.1 keycloak" | sudo tee -a /etc/hosts
```

---

### ❌ Problema 4: relation "outbox_events" does not exist

**Causa:** Hibernate no creó las tablas automáticamente.

**Solución:**
```yaml
- name: SPRING_JPA_HIBERNATE_DDL_AUTO
  value: "update"
```

---

### ❌ Problema 5: Caché de imágenes en Minikube

**Causa:** Con `imagePullPolicy: Never`, Minikube cachea la primera imagen.

**Solución:** Usar tags versionados o hacer rollout restart después de reinyectar.

---

### ❌ Problema 6: Keycloak UI no carga

**Causa:** La UI intenta cargar recursos desde `keycloak:8080` que el navegador no puede resolver.

**Solución:**
```bash
echo "127.0.0.1 keycloak" | sudo tee -a /etc/hosts
kubectl port-forward svc/keycloak 8080:8080
```
Acceder por `http://keycloak:8080`.

---

### ❌ Problema 7: Account is not fully set up al obtener token

**Causa:** El usuario tiene `Required user actions: Update Password` activo o `Temporary: ON`.

**Solución:**
1. Keycloak → Users → usuario → Details → quitar `Update Password` de Required user actions
2. Credentials → Reset password → `Temporary: OFF`

---

### ❌ Problema 8: 403 Forbidden en POST /api/v1/order

**Causa:** El SecurityConfig del gateway requiere rol `USER` para ese endpoint.

**Solución:** Keycloak → Users → usuario → Role mapping → asignar rol `USER`.

---

### ❌ Problema 9: Grafana no muestra datos de trazas ni logs

**Causa:** Las variables de entorno de OpenTelemetry en el Config Server apuntaban a `localhost:4318`. Al deshabilitar el Config Server en Kubernetes, esa configuración se perdió.

**Causa adicional:** Los nombres de variables de entorno deben coincidir exactamente con la jerarquía del YAML. `management.opentelemetry.tracing.export.otlp.endpoint` se traduce a `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`, no `MANAGEMENT_OTLP_TRACING_ENDPOINT`.

**Solución:** Agregar las variables correctas en los deployments de gateway, order e inventory:

```yaml
- name: MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT
  value: "http://lgtm:4318/v1/traces"
- name: MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT
  value: "http://lgtm:4318/v1/logs"
- name: MANAGEMENT_OTLP_METRICS_EXPORT_URL
  value: "http://lgtm:4318/v1/metrics"
- name: MANAGEMENT_TRACING_SAMPLING_PROBABILITY
  value: "1.0"
```

Luego hacer rollout restart:

```bash
kubectl rollout restart deployment/gateway-deployment
kubectl rollout restart deployment/order-deployment
kubectl rollout restart deployment/inventory-deployment
```

---

## Comandos Útiles de Diagnóstico

```bash
# Ver estado de todos los pods
kubectl get pods

# Ver logs en tiempo real
kubectl logs -f deployment/gateway-deployment

# Verificar variables de entorno de un pod
kubectl exec -it deployment/order-deployment -- env | grep OTLP

# Reiniciar un deployment sin borrarlo
kubectl rollout restart deployment/gateway-deployment

# Acceso directo a un servicio
minikube service gateway-service --url

# Borrar y recrear un deployment
kubectl delete deployment NOMBRE && kubectl apply -f k8s/ARCHIVO.yaml
```

---

## Ciclo Completo para Actualizar un Microservicio

```bash
cd nombre-del-servicio
./mvnw clean package -DskipTests
docker build -t nombre-del-servicio:latest .
minikube image load nombre-del-servicio:latest
cd ..
kubectl rollout restart deployment/nombre-deployment
```

---

## Diferencias: Minikube Local vs Producción Real

| Aspecto | Minikube (Local) | Producción (Cloud) |
|---|---|---|
| Imágenes | `minikube image load` | Registry (ECR, GCR, Docker Hub) |
| `imagePullPolicy` | `Never` | `Always` o `IfNotPresent` |
| Acceso externo | port-forward o NodePort | LoadBalancer con IP pública |
| Tunnels | Necesarios en Mac | No necesarios |
| Secrets | Variables de entorno en YAML | K8s Secrets o Vault |
| Config | Variables de entorno | ConfigMaps + Secrets |