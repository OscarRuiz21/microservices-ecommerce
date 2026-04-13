# Seccion 12: De Docker Compose a Kubernetes — Deploy Completo en Produccion

> Notas del curso de Microservicios — Migracion a Kubernetes, Dockerfiles Multistage y Deploy del sistema completo en un cluster local con Minikube

---

## Objetivo de la seccion

Pasar de un sistema que corre con `docker-compose up` en la maquina del desarrollador a un **deploy completo en Kubernetes** listo para produccion:

- **El cambio conceptual:** Reemplazo de Eureka Server por el DNS nativo de Kubernetes y del Config Server por variables de entorno y perfiles `application-k8s.yml`
- **Dockerfiles Multistage:** Optimizacion de imagenes de produccion con builds en dos etapas para reducir el tamano final
- **Recursos de Kubernetes:** Comprension practica de Deployments, Services y PersistentVolumeClaims como bloques fundamentales del cluster
- **Infraestructura en K8s:** Despliegue de bases de datos, RabbitMQ y Keycloak con sus volumenes persistentes y configuracion de DNS interno
- **Inyeccion de imagenes en Minikube:** Ciclo completo de construccion e inyeccion de imagenes locales en el cluster
- **Keycloak en Kubernetes:** Export del realm, ConfigMap para importacion automatica y resolucion del problema de hostname en Mac
- **Observabilidad en K8s:** Activacion de OpenTelemetry mediante variables de entorno para conectar los microservicios con Grafana LGTM dentro del cluster
- **Prueba end-to-end:** Verificacion del sistema completo desde Postman a traves del gateway hasta RabbitMQ y el notification-service

---

## 1. El Problema con Docker Compose

### Desarrollo vs Produccion

Docker Compose es perfecto para desarrollo local: un `docker-compose up` y todo el sistema arranca. Pero en produccion tiene limitaciones fundamentales:

| Aspecto | Docker Compose | Produccion necesita |
|---|---|---|
| **Autocuracion** | Si un contenedor muere, nadie lo levanta | Deteccion automatica de fallos y reinicio |
| **Escalado** | Manual (`scale: 3` y reiniciar) | Escalado automatico basado en carga |
| **Balanceo de carga** | No tiene | Distribucion de trafico entre replicas |
| **Alta disponibilidad** | Single point of failure (la maquina) | Distribucion entre multiples nodos |
| **Descubrimiento** | Red Docker con nombres de contenedor | DNS nativo del cluster |

**Docker Compose es el entorno de desarrollo. Kubernetes es el entorno de produccion.**

### Que es Kubernetes

Kubernetes (K8s) es un **orquestador de contenedores**. Funciona como un director de orquesta: vos le decis **que queres** (3 replicas de order-service, una base de datos con 1Gi de disco) y K8s se encarga de **como lograrlo** — donde poner los pods, como balancear el trafico, como recuperarse si algo falla.

```
Modelo declarativo:

  Vos: "Quiero 3 replicas de order-service"
       │
       ▼
  Kubernetes: evalua el estado actual del cluster
       │
       ▼
  Si hay 2 → crea 1 mas
  Si hay 4 → elimina 1
  Si 1 muere → crea otro automaticamente
```

### Minikube — Kubernetes local

Minikube crea un cluster de Kubernetes **real** en tu maquina, dentro de un contenedor Docker. Es un simulador de vuelo: mismo software, mismo comportamiento, pero sin el costo de la nube.

```bash
minikube start --driver=docker --cpus=4 --memory=6144
```

---

## 2. El Gran Cambio: De Eureka + Config Server a Kubernetes

Este es el concepto central de toda la migracion. En desarrollo local, el sistema depende de dos componentes de Spring Cloud:

```
Desarrollo Local:

  Microservicio → Eureka Server → "Donde esta order-service?" → lb://ORDER-SERVICE
  Microservicio → Config Server → "Dame mi configuracion" → GitHub repo privado
```

En Kubernetes, ambos componentes se vuelven **innecesarios** porque K8s resuelve nativamente lo que ellos hacian:

```
Kubernetes:

  Microservicio → DNS de K8s → "order-service" → Pod del order-service
  Microservicio → Variables de entorno / application-k8s.yml → Configuracion directa
```

### 2.1 Por que Kubernetes reemplaza a Eureka

Cuando creas un **Service** en K8s con nombre `order-service`, el DNS interno del cluster resuelve automaticamente ese nombre al pod correcto. No hace falta un servidor de descubrimiento externo.

```java
// ANTES (con Eureka) — necesita que Eureka este corriendo
.uri("lb://ORDER-SERVICE")

// DESPUES (con K8s DNS) — K8s resuelve esto nativamente
.uri("http://order-service:8082")
```

### 2.2 Por que Kubernetes reemplaza a Config Server

En K8s se puede pasar configuracion a cada pod mediante **variables de entorno** en el Deployment YAML, o mediante perfiles de Spring Boot (`application-k8s.yml`). Ya no se necesita un servidor centralizado que se conecte a GitHub.

### 2.3 Cambios en GatewayConfig.java

El archivo de configuracion de rutas pasa de usar Eureka load-balancing a DNS directo de K8s:

```java
@Configuration
public class GatewayConfig {

    @Bean
    public RouteLocator routeLocator(RouteLocatorBuilder builder){
        return builder.routes()
                .route("product-service", r -> r
                        .path("/api/v1/product/**")
                        .uri("http://product-service:8081"))    // DNS de K8s
                .route("order-service", r -> r
                        .path("/api/v1/order/**")
                        .uri("http://order-service:8082"))      // DNS de K8s
                .route("inventory-service", r -> r
                        .path("/api/v1/inventory/**")
                        .uri("http://inventory-service:8083"))   // DNS de K8s
                .build();
    }
}
```

**Nota:** Las rutas definidas en codigo Java tienen prioridad sobre las definidas en YAML. Si tenes rutas en ambos lados, las del codigo ganan.

### 2.4 application-k8s.yml — Perfil especifico para Kubernetes

Se crea un archivo `application-k8s.yml` en el api-gateway que deshabilita Config Server, Eureka y configura las rutas con DNS de K8s:

```yaml
spring:
  cloud:
    config:
      enabled: false       # No hay Config Server en K8s
    discovery:
      enabled: false       # No hay Eureka en K8s
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
    host: rabbitmq         # DNS del Service de RabbitMQ en K8s
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://keycloak:8080/realms/ecommerce-realm
```

Este perfil se activa cuando el pod corre con la variable de entorno `SPRING_PROFILES_ACTIVE=k8s`.

---

## 3. Dockerfiles Multistage — Imagenes Optimizadas

### Concepto

Un Dockerfile Multistage separa la **construccion** de la **ejecucion** en dos etapas dentro del mismo archivo. La primera etapa compila el proyecto con todas las herramientas necesarias (JDK, Maven, codigo fuente). La segunda etapa solo copia el JAR resultante sobre una imagen minima de runtime (JRE). El resultado es una imagen de produccion mucho mas liviana.

### 3.1 Estructura del Dockerfile

```dockerfile
# ---- ETAPA 1: CONSTRUCCION ----
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY mvnw .
COPY .mvn .mvn
RUN ./mvnw dependency:go-offline -B
COPY src ./src
RUN ./mvnw clean package -DskipTests

# ---- ETAPA 2: EJECUCION ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 3.2 Que hace cada etapa

| Etapa | Imagen base | Contenido | Tamano aproximado |
|---|---|---|---|
| **Builder** | `eclipse-temurin:21-jdk-alpine` | JDK completo + Maven + codigo fuente + dependencias | ~500MB+ |
| **Ejecucion** | `eclipse-temurin:21-jre-alpine` | Solo JRE + el JAR compilado | ~200MB |

La imagen final **no contiene** el JDK, ni Maven, ni el codigo fuente, ni las dependencias de compilacion. Solo el runtime minimo necesario para ejecutar el JAR.

### 3.3 Optimizacion: Dependency Caching

```dockerfile
COPY pom.xml .
COPY mvnw .
COPY .mvn .mvn
RUN ./mvnw dependency:go-offline -B    # Descarga TODAS las dependencias primero
COPY src ./src                          # Luego copia el codigo fuente
RUN ./mvnw clean package -DskipTests    # Finalmente compila
```

**Por que este orden importa:** Docker cachea cada capa. Si solo cambiaste codigo fuente (no dependencias), Docker reutiliza la capa de `dependency:go-offline` del build anterior. Esto acelera los rebuilds drasticamente porque no re-descarga las dependencias cada vez.

### 3.4 El mismo Dockerfile para todos los servicios

Cada microservicio tiene su propio Dockerfile con la misma estructura, solo cambia el `EXPOSE`:

| Servicio | Puerto (`EXPOSE`) |
|---|---|
| api-gateway | 9000 |
| product-service | 8081 |
| order-service | 8082 |
| inventory-service | 8083 |
| notification-service | 8084 |

---

## 4. Los Tres Conceptos Fundamentales de Kubernetes

Para desplegar el sistema solo se necesitan tres tipos de recursos de K8s. Todo lo demas se construye sobre estos bloques:

### 4.1 Deployment — Que correr y como

Un Deployment es una **declaracion de intencion**. Le decis a Kubernetes que imagen correr, cuantas replicas, y que variables de entorno necesita. K8s se encarga de mantener ese estado.

```yaml
kind: Deployment
spec:
  replicas: 1
  template:
    spec:
      containers:
        - name: order-service
          image: order-service:latest
          imagePullPolicy: Never         # Usa imagen local (inyectada en Minikube)
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "k8s"
```

Si el contenedor se cae, Kubernetes lo levanta automaticamente. No se manejan contenedores — se manejan **declaraciones**.

### 4.2 Service — DNS estable entre pods

Los pods son efimeros — mueren y renacen con IPs distintas. El **Service** es la capa estable: un nombre fijo que se convierte en DNS interno del cluster.

```yaml
kind: Service
metadata:
  name: order-service          # Este nombre se resuelve via DNS dentro del cluster
spec:
  selector:
    app: order-service         # Conecta con pods que tengan este label
  ports:
    - port: 8082
      targetPort: 8082
```

Cuando otro pod hace `http://order-service:8082`, el DNS de K8s resuelve ese nombre al pod correcto. **Este es el mecanismo que reemplaza a Eureka.**

Hay dos tipos de Service relevantes:

| Tipo | Acceso | Uso |
|---|---|---|
| **ClusterIP** (default) | Solo dentro del cluster | Comunicacion entre microservicios (order-service, inventory-service) |
| **NodePort** | Desde fuera del cluster via un puerto fijo (30000-32767) | Servicios que necesitan acceso externo (Gateway, Keycloak, Grafana) |

### 4.3 PersistentVolumeClaim (PVC) — Almacenamiento que sobrevive reinicios

Sin un PVC, cuando un pod se reinicia **todo lo que habia en disco desaparece**. Las bases de datos perderian todos los datos. Un PVC es una solicitud formal: "necesito 1Gi de disco que sobreviva reinicios".

```yaml
kind: PersistentVolumeClaim
metadata:
  name: order-db-pvc
spec:
  accessModes: [ReadWriteOnce]
  resources:
    requests:
      storage: 1Gi
```

Kubernetes provee el volumen. Los datos quedan guardados aunque el pod muera y se recree.

---

## 5. Infraestructura en Kubernetes

### 5.1 Estructura de archivos

```
K8s/
├── infrastructure-pvcs.yaml      # Volumenes persistentes (MongoDB, MySQL, PostgreSQL, Keycloak DB)
├── databases.yaml                # Deployments + Services de MongoDB, MySQL, PostgreSQL (x2)
├── rabbitmq-pvc.yaml             # Volumen persistente para RabbitMQ
├── rabbitmq-deployment.yaml      # Deployment de RabbitMQ
├── rabbitmq-service.yaml         # Service de RabbitMQ (NodePort para Management UI)
├── security-observability.yaml   # Keycloak + Grafana LGTM
├── gateway-deployment.yaml       # API Gateway
├── gateway-service.yaml          # Service del Gateway (NodePort 30000)
├── product-deployment.yaml       # Product Service + Service
├── order-deployment.yaml         # Order Service + Service
├── inventory-deployment.yaml     # Inventory Service + Service
└── notification-deployment.yaml  # Notification Service + Service
```

### 5.2 Bases de datos

Cada base de datos tiene su propio Deployment, Service y PVC:

| Base de datos | Imagen | Servicio que la usa | PVC |
|---|---|---|---|
| MongoDB | `mongo:7.0.4` | product-service | `mongo-pvc` |
| MySQL | `mysql:8` | inventory-service | `inventory-db-pvc` |
| PostgreSQL | `postgres:16-alpine` | order-service | `order-db-pvc` |
| PostgreSQL | `postgres:16-alpine` | Keycloak | `keycloak-db-pvc` |

Cada base de datos expone un **Service ClusterIP** con un nombre DNS que los microservicios usan para conectarse:

```yaml
# El microservicio se conecta asi:
- name: SPRING_DATASOURCE_URL
  value: "jdbc:postgresql://order-db:5432/order-db"
#                           ^^^^^^^^
#                           Nombre del Service de K8s → DNS interno
```

### 5.3 RabbitMQ

RabbitMQ necesita su propio PVC para persistir las colas y mensajes, y un Service de tipo **NodePort** para exponer la UI de Management:

```yaml
# rabbitmq-service.yaml
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
      port: 5672            # Puerto AMQP (mensajeria)
      targetPort: 5672
    - name: http
      port: 15672           # Puerto Management UI
      targetPort: 15672
      nodePort: 31672       # Acceso externo (Linux)
```

Los microservicios se conectan a RabbitMQ con `SPRING_RABBITMQ_HOST=rabbitmq` — el DNS del Service.

### 5.4 Keycloak — Importacion automatica del realm

Keycloak en K8s necesita resolver dos problemas: importar el realm existente y configurar el hostname correctamente.

**Importacion del realm via ConfigMap:**

El realm exportado de la instalacion local se monta como volumen en el pod de Keycloak:

```yaml
# En el Deployment de Keycloak:
command: ["/opt/keycloak/bin/kc.sh", "start-dev", "--import-realm"]
volumeMounts:
  - name: realm-config
    mountPath: /opt/keycloak/data/import/ecommerce-realm.json
    subPath: ecommerce-realm.json
volumes:
  - name: realm-config
    configMap:
      name: keycloak-realm-config    # ConfigMap creado con kubectl
```

El ConfigMap se crea previamente con:

```bash
kubectl create configmap keycloak-realm-config --from-file=ecommerce-realm.json
```

**Configuracion del hostname:**

Para que los tokens JWT sean validos, el `issuer` del token debe coincidir con el endpoint que el gateway usa para validar. Se configura un hostname fijo:

```yaml
- name: KC_HOSTNAME
  value: "keycloak"
- name: KC_HOSTNAME_PORT
  value: "8080"
- name: KC_HOSTNAME_STRICT
  value: "false"
- name: KC_HOSTNAME_STRICT_BACKCHANNEL
  value: "false"
```

Y en el archivo `/etc/hosts` de la maquina local:

```bash
echo "127.0.0.1 keycloak" | sudo tee -a /etc/hosts
```

Esto asegura que el token emitido con `iss: http://keycloak:8080/realms/ecommerce-realm` sea resuelto tanto dentro del cluster (por DNS de K8s) como fuera (por el /etc/hosts).

### 5.5 Grafana LGTM

Grafana LGTM se despliega con la imagen `grafana/otel-lgtm:latest` que incluye Grafana, Loki, Tempo y Prometheus en un solo contenedor:

```yaml
# En security-observability.yaml:
containers:
  - name: lgtm
    image: grafana/otel-lgtm:latest
    ports:
      - containerPort: 3000     # Grafana UI
      - containerPort: 4317     # gRPC OTLP
      - containerPort: 4318     # HTTP OTLP
```

El Service de tipo NodePort expone Grafana en el puerto 31300 (Linux) o via port-forward (Mac/Windows).

---

## 6. Deployments de los Microservicios

### 6.1 Patron comun

Cada microservicio sigue el mismo patron en su Deployment:

1. **`imagePullPolicy: Never`** — La imagen no se descarga de un registry, se usa la inyectada localmente en Minikube
2. **`SPRING_PROFILES_ACTIVE: k8s`** — Activa el perfil de Kubernetes
3. **`SPRING_CLOUD_CONFIG_ENABLED: false`** — Deshabilita Config Server
4. **`EUREKA_CLIENT_ENABLED: false`** — Deshabilita Eureka
5. **Variables de conexion** — Base de datos, RabbitMQ, Keycloak apuntan a nombres DNS de K8s

### 6.2 Ejemplo: order-service

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
            # Seguridad
            - name: SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI
              value: "http://keycloak:8080/realms/ecommerce-realm"
            # Observabilidad
            - name: MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT
              value: "http://lgtm:4318/v1/traces"
            - name: MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT
              value: "http://lgtm:4318/v1/logs"
            - name: MANAGEMENT_OTLP_METRICS_EXPORT_URL
              value: "http://lgtm:4318/v1/metrics"
            - name: MANAGEMENT_TRACING_SAMPLING_PROBABILITY
              value: "1.0"
            # Base de datos
            - name: SPRING_DATASOURCE_URL
              value: "jdbc:postgresql://order-db:5432/order-db"
            - name: SPRING_DATASOURCE_USERNAME
              value: "admin"
            - name: SPRING_DATASOURCE_PASSWORD
              value: "admin"
            # Mensajeria
            - name: SPRING_RABBITMQ_HOST
              value: "rabbitmq"
            - name: SPRING_JPA_HIBERNATE_DDL_AUTO
              value: "update"
```

**Observacion:** Las variables de entorno de Spring Boot siguen la convencion de binding: `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` corresponde a `management.opentelemetry.tracing.export.otlp.endpoint` en YAML. Spring Boot resuelve esto automaticamente.

### 6.3 Observabilidad: De localhost a DNS de K8s

En desarrollo local, OpenTelemetry exportaba a `http://localhost:4318`. En K8s, el container de Grafana LGTM se llama `lgtm`:

| Senal | Endpoint en Docker Compose | Endpoint en K8s |
|---|---|---|
| Trazas | `http://localhost:4318/v1/traces` | `http://lgtm:4318/v1/traces` |
| Logs | `http://localhost:4318/v1/logs` | `http://lgtm:4318/v1/logs` |
| Metricas | `http://localhost:4318/v1/metrics` | `http://lgtm:4318/v1/metrics` |

Estos endpoints se inyectan como variables de entorno en los Deployments del Gateway, Order Service e Inventory Service.

### 6.4 Services de los microservicios

| Servicio | Tipo | Puerto | NodePort |
|---|---|---|---|
| `gateway-service` | NodePort | 9000 | 30000 |
| `product-service` | ClusterIP | 8081 | — |
| `order-service` | ClusterIP | 8082 | — |
| `inventory-service` | ClusterIP | 8083 | — |
| `notification-service` | ClusterIP | 8084 | — |

Solo el Gateway es NodePort porque es el **unico punto de entrada** desde fuera del cluster. El resto usa ClusterIP porque solo se comunican entre si dentro del cluster.

---

## 7. Inyeccion de Imagenes en Minikube

### Concepto

Minikube corre en su propio contenedor separado de Docker Desktop. Las imagenes que se construyen con `docker build` **no son visibles automaticamente** para Minikube. Hay que inyectarlas explicitamente.

### 7.1 Ciclo completo: Build → Inyectar → Deploy

Para cada microservicio, el ciclo es:

```bash
# 1. Compilar el JAR con Maven
cd order-service && ./mvnw clean package -DskipTests

# 2. Construir la imagen Docker
docker build -t order-service:latest .

# 3. Inyectar la imagen en Minikube
minikube image load order-service:latest

# 4. Volver al directorio raiz
cd ..
```

Para todos los microservicios de una vez:

```bash
cd api-gateway && ./mvnw clean package -DskipTests && docker build -t api-gateway:latest . && minikube image load api-gateway:latest && cd ..
cd order-service && ./mvnw clean package -DskipTests && docker build -t order-service:latest . && minikube image load order-service:latest && cd ..
cd product-service && ./mvnw clean package -DskipTests && docker build -t product-service:latest . && minikube image load product-service:latest && cd ..
cd inventory-service && ./mvnw clean package -DskipTests && docker build -t inventory-service:latest . && minikube image load inventory-service:latest && cd ..
cd notification-service && ./mvnw clean package -DskipTests && docker build -t notification-service:latest . && minikube image load notification-service:latest && cd ..
```

### 7.2 El problema del cache de imagenes

Con `imagePullPolicy: Never`, Minikube cachea la primera imagen con tag `latest`. Si reconstruis la imagen y la reinyectas, el pod puede seguir usando la version anterior.

**Soluciones:**

1. **Tags versionados:** Usar `order-service:v2` en lugar de `latest` y actualizar el YAML
2. **Rollout restart:** Forzar la recreacion del pod despues de reinyectar:

```bash
kubectl rollout restart deployment/order-deployment
```

---

## 8. Proceso de Deploy Completo

### Prerrequisitos

- Docker Desktop instalado y corriendo
- Minikube instalado (`brew install minikube`)
- kubectl instalado

### Paso 1: Iniciar Minikube

```bash
minikube start --driver=docker --cpus=4 --memory=6144
```

### Paso 2: Exportar el realm de Keycloak

1. Levantar Keycloak local: `docker-compose up -d keycloak keycloak-db`
2. Entrar a `http://localhost:8080` con `admin` / `admin`
3. Seleccionar el realm `ecommerce-realm`
4. Ir a **Realm settings** → **Action** → **Export**
5. Activar **Export groups and roles** y **Export clients** → **Export**
6. Guardar el `ecommerce-realm.json` en la raiz del proyecto
7. Bajar Docker Compose: `docker-compose down`

### Paso 3: Agregar keycloak al archivo hosts

```bash
# Mac y Linux:
echo "127.0.0.1 keycloak" | sudo tee -a /etc/hosts

# Windows: editar C:\Windows\System32\drivers\etc\hosts como administrador
# Agregar: 127.0.0.1 keycloak
```

### Paso 4: Crear el ConfigMap del realm

```bash
kubectl create configmap keycloak-realm-config --from-file=ecommerce-realm.json
```

### Paso 5: Aplicar la infraestructura

```bash
kubectl apply -f K8s/
```

### Paso 6: Construir e inyectar las imagenes

```bash
cd api-gateway && ./mvnw clean package -DskipTests && docker build -t api-gateway:latest . && minikube image load api-gateway:latest && cd ..
cd order-service && ./mvnw clean package -DskipTests && docker build -t order-service:latest . && minikube image load order-service:latest && cd ..
cd product-service && ./mvnw clean package -DskipTests && docker build -t product-service:latest . && minikube image load product-service:latest && cd ..
cd inventory-service && ./mvnw clean package -DskipTests && docker build -t inventory-service:latest . && minikube image load inventory-service:latest && cd ..
cd notification-service && ./mvnw clean package -DskipTests && docker build -t notification-service:latest . && minikube image load notification-service:latest && cd ..
```

### Paso 7: Verificar que todo esta corriendo

```bash
kubectl get pods
```

Todos deben estar en estado `Running`.

### Paso 8: Crear usuarios en Keycloak

```bash
kubectl port-forward svc/keycloak 8080:8080
```

Entrar a `http://keycloak:8080` con `admin` / `admin` y crear los usuarios.

**Puntos criticos al crear usuarios:**
- En `Required user actions` — asegurarse de que **NO** tenga `Update Password` seleccionado
- En `Credentials` → `Reset password` → poner `Temporary: OFF`
- El usuario que va a hacer POST a `/api/v1/order` necesita el rol `USER`, no `ADMIN`

### Paso 9: Activar observabilidad

Grafana LGTM ya esta definido en el YAML. Para activar la telemetria, hacer un rollout restart de los servicios con OpenTelemetry:

```bash
kubectl rollout restart deployment/gateway-deployment
kubectl rollout restart deployment/order-deployment
kubectl rollout restart deployment/inventory-deployment
```

---

## 9. Acceso al Cluster segun Sistema Operativo

### Mac y Windows — Port Forwarding obligatorio

En Mac y Windows, Minikube corre dentro de un contenedor Docker que agrega una capa de aislamiento. Los NodePorts **no son accesibles directamente**. Es necesario abrir un port-forward por cada servicio:

```bash
# Terminal 1: Acceso a Keycloak
kubectl port-forward svc/keycloak 8080:8080

# Terminal 2: Acceso al Gateway
kubectl port-forward svc/gateway-service 30000:9000

# Terminal 3: Acceso a Grafana
kubectl port-forward svc/lgtm 3000:3000
```

**Importante:** Si reiniciar un pod (por ejemplo con `kubectl rollout restart`), el port-forward se corta y hay que volver a abrirlo.

### Linux — Acceso directo por NodePort

En Linux, Minikube corre de forma nativa. Los NodePorts son accesibles directamente:

| Servicio | URL |
|---|---|
| Gateway | `http://localhost:30000` |
| Keycloak | `http://localhost:31080` |
| Grafana | `http://localhost:31300` |
| RabbitMQ Management | `http://localhost:31672` |

---

## 10. Prueba End-to-End con Postman

### Configuracion de Postman

| Campo | Valor |
|---|---|
| Type | OAuth 2.0 |
| Grant Type | Password Credentials |
| Access Token URL | `http://keycloak:8080/realms/ecommerce-realm/protocol/openid-connect/token` |
| Client ID | `api-gateway-client` |
| Client Secret | (Keycloak → Clients → api-gateway-client → Credentials) |
| Username | tu usuario |
| Password | tu contrasena |

### Endpoints disponibles

```
GET  http://localhost:30000/api/v1/product       (requiere autenticacion)
POST http://localhost:30000/api/v1/product       (requiere rol ADMIN)
GET  http://localhost:30000/api/v1/inventory      (requiere autenticacion)
POST http://localhost:30000/api/v1/inventory     (requiere rol ADMIN)
GET  http://localhost:30000/api/v1/order          (requiere rol ADMIN o USER)
POST http://localhost:30000/api/v1/order          (requiere rol USER)
```

### El flujo completo

```
Postman (con token JWT)
    │
    ▼
Gateway (NodePort 30000) ──[valida JWT con Keycloak]──→ Keycloak
    │
    ▼
order-service (ClusterIP 8082) ──[guarda orden]──→ PostgreSQL (order-db)
    │                            ──[publica evento]──→ RabbitMQ
    │
    ├──→ inventory-service (ClusterIP 8083) ──[descuenta stock]──→ MySQL (inventory-db)
    │                                        ──[publica confirmacion]──→ RabbitMQ
    │
    └──→ notification-service (ClusterIP 8084) ──[envia email]──→ Mailtrap
```

Si la observabilidad esta activa, cada paso genera trazas, logs y metricas que se visualizan en Grafana (`http://localhost:3000`).

---

## 11. Problemas Encontrados y Soluciones

### Problema 1: ReactiveJwtDecoder bean not found

**Sintoma:** El gateway no arranca, falla con error de bean.

**Causa:** `application.yaml` tenia `spring.config.import: configserver:http://localhost:8888`. Spring intentaba conectarse al Config Server durante el bootstrap.

**Solucion:** Deshabilitar Config Server y Eureka via variables de entorno:
```yaml
- name: SPRING_CLOUD_CONFIG_ENABLED
  value: "false"
- name: EUREKA_CLIENT_ENABLED
  value: "false"
- name: SPRING_CLOUD_DISCOVERY_ENABLED
  value: "false"
```

### Problema 2: Gateway devuelve 503 en todos los endpoints

**Causa 1:** Las rutas estaban en el Config Server con `lb://ORDER-SERVICE`. Al deshabilitar Config Server, las rutas desaparecieron.

**Solucion:** Mover las rutas al `GatewayConfig.java` con URIs de DNS de K8s.

**Causa 2:** Minikube cachea imagenes con tag `latest`.

**Solucion:** Usar tags versionados o hacer rollout restart:
```bash
docker build -t api-gateway:v2 .
minikube image load api-gateway:v2
kubectl delete deployment gateway-deployment
kubectl apply -f K8s/gateway-deployment.yaml
```

### Problema 3: Token JWT invalido — Issuer no coincide

**Causa:** El token tenia `iss: http://127.0.0.1:RANDOM_PORT` pero el gateway validaba contra `http://keycloak:8080`.

**Solucion:** Configurar Keycloak con hostname fijo (`KC_HOSTNAME=keycloak`) y agregar `keycloak` al `/etc/hosts`.

### Problema 4: relation "outbox_events" does not exist

**Causa:** Hibernate no creo las tablas automaticamente en la nueva base de datos de K8s.

**Solucion:** Agregar la variable de entorno:
```yaml
- name: SPRING_JPA_HIBERNATE_DDL_AUTO
  value: "update"
```

### Problema 5: Cache de imagenes en Minikube

**Causa:** Con `imagePullPolicy: Never`, Minikube cachea la primera imagen con tag `latest`.

**Solucion:** Usar tags versionados o hacer `kubectl rollout restart` despues de reinyectar la imagen.

---

## Archivos clave de esta seccion

| Archivo | Que hace |
|---|---|
| `api-gateway/Dockerfile` | Multistage build: JDK Alpine (builder) → JRE Alpine (runtime). Puerto 9000 |
| `order-service/Dockerfile` | Misma estructura multistage. Puerto 8082 |
| `product-service/Dockerfile` | Misma estructura multistage. Puerto 8081 |
| `inventory-service/Dockerfile` | Misma estructura multistage. Puerto 8083 |
| `notification-service/Dockerfile` | Misma estructura multistage. Puerto 8084 |
| `api-gateway/src/main/resources/application-k8s.yml` | Perfil K8s: deshabilita Config Server y Eureka, rutas con DNS de K8s |
| `api-gateway/.../config/GatewayConfig.java` | Rutas con URIs de DNS de K8s (`http://order-service:8082`) |
| `ecommerce-realm.json` | Realm exportado de Keycloak para importacion automatica via ConfigMap |
| `K8s/infrastructure-pvcs.yaml` | PVCs para MongoDB, MySQL, PostgreSQL (order), PostgreSQL (keycloak) |
| `K8s/databases.yaml` | Deployments + Services de las 4 bases de datos |
| `K8s/rabbitmq-deployment.yaml` | Deployment de RabbitMQ con PVC |
| `K8s/rabbitmq-service.yaml` | Service NodePort de RabbitMQ (AMQP 5672 + Management 15672) |
| `K8s/security-observability.yaml` | Keycloak (con ConfigMap del realm) + Grafana LGTM (con puertos OTLP) |
| `K8s/gateway-deployment.yaml` | Gateway con variables de OTel, JWT y deshabilitacion de Eureka/Config |
| `K8s/gateway-service.yaml` | Service NodePort del Gateway (puerto 30000) |
| `K8s/product-deployment.yaml` | Product Service con conexion a MongoDB via DNS |
| `K8s/order-deployment.yaml` | Order Service con OTel, PostgreSQL, RabbitMQ via DNS |
| `K8s/inventory-deployment.yaml` | Inventory Service con OTel, MySQL, RabbitMQ via DNS |
| `K8s/notification-deployment.yaml` | Notification Service con RabbitMQ y Mailtrap via DNS |
