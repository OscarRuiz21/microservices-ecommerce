# Seccion 11: Observabilidad Unificada con OpenTelemetry y Grafana LGTM

> Notas del curso de Microservicios — Trazas distribuidas, Logs correlacionados y Metricas con el stack Grafana

---

## Objetivo de la seccion

Pasar de un sistema donde cada microservicio es una **caja negra** que solo imprime logs a su propia consola, a uno con **observabilidad completa** donde:

- Se puede seguir el recorrido de **una peticion a traves de multiples servicios** con un Trace ID unico
- Los **logs de todos los servicios** se centralizan en Loki y se correlacionan con las trazas
- Las **metricas** (latencia, throughput, errores, JVM) se recolectan automaticamente en Prometheus
- Todo se visualiza en **Grafana** desde un solo panel, cruzando las tres senales
- Se usa **OpenTelemetry (OTel)** como estandar unico, sin vendor lock-in

---

## 1. El Problema de la Caja Negra

### Monitorear vs Observar

En un sistema distribuido con 5+ microservicios, cuando algo falla surge la pregunta: **donde esta el problema?** Los logs estan repartidos en 5 consolas diferentes, cada una con su propio formato y timestamp. No hay forma de saber que log de Order Service corresponde a que log de Inventory Service para una misma peticion.

| | Monitoreo Tradicional | Observabilidad |
|---|---|---|
| **Pregunta** | **QUE** fallo? | **POR QUE** fallo? |
| **Respuesta** | "Inventory Service devolvio 500" | "La orden ORD-456 fallo porque Inventory tardo 8s en responder debido a un lock en la tabla de stock" |
| **Alcance** | Un servicio a la vez | El recorrido completo de la peticion |
| **Contexto** | Aislado | Correlacionado entre servicios |

**Monitoreo** te dice que algo esta roto. **Observabilidad** te permite hacer preguntas que no anticipaste cuando escribiste el codigo.

### Los 3 Pilares de la Observabilidad

| Pilar | Que es | Para que | Ejemplo |
|---|---|---|---|
| **Metricas** | Numeros agregados en el tiempo (latencia, requests/s, % CPU) | Detectar **que** hay un problema | "La latencia del 95th percentil subio a 3 segundos" |
| **Trazas** | El recorrido de UNA peticion entre servicios (Trace ID + Spans) | Entender **por que** hay un problema | "Order Service tardo 5s esperando a Inventory en el span `reduceStock`" |
| **Logs** | Eventos discretos con contexto (timestamp, nivel, mensaje, Trace ID) | Los **detalles especificos** | "Stock insuficiente para SKU iphone-15 en Inventory Service" |

**El poder real:** Estos tres pilares estan **correlacionados por Trace ID**. Un spike en latencia (metrica) te lleva a una traza especifica, que te muestra los logs relevantes. Todo conectado.

---

## 2. OpenTelemetry — El Estandar Universal

### Concepto

**OpenTelemetry (OTel)** es un estandar de la CNCF (Cloud Native Computing Foundation) que unifica la recoleccion de datos de observabilidad en un solo protocolo: **OTLP (OpenTelemetry Protocol)**.

Antes de OTel, cada herramienta tenia su propio formato: Zipkin usaba uno, Jaeger otro, Prometheus otro. Con OTLP, instrumentas tu codigo **una vez** y lo envias a **cualquier backend** compatible (Grafana, Datadog, New Relic, AWS X-Ray).

### Conceptos clave

| Concepto | Que es |
|---|---|
| **Trace** | El recorrido completo de una peticion. Tiene un `traceId` unico que viaja entre servicios |
| **Span** | Un segmento dentro del trace. Cada operacion (HTTP call, query a BD, publish a RabbitMQ) es un span |
| **Trace ID** | Identificador unico de 128 bits que conecta todos los spans de una misma peticion |
| **Span ID** | Identificador unico de cada span individual dentro del trace |
| **OTLP** | Protocolo estandar de transporte. Puede ir por HTTP (puerto 4318) o gRPC (puerto 4317) |
| **Exporter** | Componente que envia los datos al backend (Tempo, Loki, Prometheus) |

### Spring Boot 4 — Observabilidad nativa

Antes de Spring Boot 4, integrar OpenTelemetry requeria agentes Java externos (`-javaagent:opentelemetry.jar`), librerias alfa/beta, y configuracion manual de exporters. Con Spring Boot 4:

- **Starter oficial:** `spring-boot-starter-opentelemetry` — una sola dependencia
- **Autoconfiguracion out-of-the-box** — detecta el endpoint OTLP y exporta automaticamente
- **Sin agentes externos** — Spring habla OTLP directamente

### Actuator y OpenTelemetry: Complementarios

No son competidores — cubren necesidades distintas:

| Aspecto | Spring Actuator | OpenTelemetry |
|---|---|---|
| **Modelo** | Pull-based (Prometheus raspa endpoints) | Push-based (el servicio envia datos activamente) |
| **Fortaleza** | Health checks, metricas locales (JVM, pools, HTTP) | Trazas distribuidas, contexto end-to-end |
| **Ideal para** | Monitoreo de instancia individual | Debugging distribuido entre servicios |
| **En este proyecto** | `/actuator/health`, `/actuator/prometheus` | Traces en Tempo, Logs en Loki, Metrics en Prometheus |

---

## 3. Stack Grafana LGTM — Infraestructura de Observabilidad

### Concepto

**LGTM** es el acronimo del stack de Grafana: **Loki** (logs), **Grafana** (visualizacion), **Tempo** (trazas) y **Mimir/Prometheus** (metricas). Grafana ofrece una imagen Docker todo-en-uno que incluye los 4 componentes:

### 3.1 Docker Compose

```yaml
grafana-lgtm:
  image: 'grafana/otel-lgtm:latest'
  ports:
    - '3000:3000'    # Grafana UI — panel de visualizacion
    - '4317:4317'    # gRPC — entrada para datos OTLP (no se usa en este proyecto)
    - '4318:4318'    # HTTP — entrada OTLP para logs, trazas y metricas
  environment:
    - OTEL_METRIC_EXPORT_INTERVAL=5000   # Exportar metricas cada 5 segundos
```

| Puerto | Servicio | Para que |
|---|---|---|
| `3000` | Grafana UI | Dashboards, exploracion de logs/trazas/metricas en el navegador |
| `4317` | gRPC OTLP | Entrada de datos via gRPC (mas eficiente, no usado aqui) |
| `4318` | HTTP OTLP | Entrada de datos via HTTP — los microservicios envian aqui sus datos |

La imagen `grafana/otel-lgtm` incluye internamente: Grafana, Loki, Tempo, Prometheus y un OpenTelemetry Collector preconfigurado que recibe datos en el puerto 4318 y los enruta al backend correspondiente.

### 3.2 Como encaja cada componente

```
Microservicios (Spring Boot)
     │
     │  OTLP HTTP (puerto 4318)
     │
     ▼
┌────────────────────────────────────────────────────┐
│           grafana/otel-lgtm (Docker)               │
│                                                    │
│  ┌──────────┐  ┌──────────┐  ┌───────────────┐    │
│  │  Loki    │  │  Tempo   │  │  Prometheus   │    │
│  │  (Logs)  │  │ (Trazas) │  │  (Metricas)   │    │
│  └────┬─────┘  └────┬─────┘  └──────┬────────┘    │
│       │             │               │              │
│       └─────────────┼───────────────┘              │
│                     │                              │
│              ┌──────┴──────┐                       │
│              │   Grafana   │ ← puerto 3000         │
│              │   (Panel)   │                       │
│              └─────────────┘                       │
└────────────────────────────────────────────────────┘
```

---

## 4. Integracion de OpenTelemetry en los Microservicios

### 4.1 Dependencias

Cada microservicio que participa en la observabilidad necesita dos dependencias:

```xml
<!-- Starter oficial de Spring Boot 4 para OpenTelemetry -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-opentelemetry</artifactId>
</dependency>

<!-- Appender de Logback para redirigir logs a OTLP -->
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-logback-appender-1.0</artifactId>
    <version>2.21.0-alpha</version>
</dependency>
```

- **`spring-boot-starter-opentelemetry`**: Autoconfigura la exportacion de trazas y metricas via OTLP. Detecta el endpoint configurado en el YAML y comienza a enviar datos automaticamente.
- **`opentelemetry-logback-appender-1.0`**: Puente entre Logback (el sistema de logging de Spring) y OpenTelemetry. Permite que los logs de consola tambien se exporten via OTLP a Loki.

Se aplican en: **Order Service, Inventory Service y API Gateway**.

### 4.2 Configuracion YAML (Config Server)

La configuracion de observabilidad se agrega al YAML de cada servicio en el Config Server. Ejemplo con `order-service.yml`:

```yaml
# CONFIGURACION DE OBSERVABILIDAD UNIFICADA (Traces, Logs y Metrics)
management:
  tracing:
    sampling:
      probability: 1.0   # Captura el 100% de las trazas (en produccion se baja a 0.1 o 0.01)
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: http://localhost:4318/v1/traces    # Trazas → Tempo
    logging:
      export:
        otlp:
          endpoint: http://localhost:4318/v1/logs      # Logs → Loki
  otlp:
    metrics:
      export:
        url: http://localhost:4318/v1/metrics          # Metricas → Prometheus

# CORRELACION DE LOGS (TraceID y SpanID en consola)
logging:
  pattern:
    level: "%5p [${spring.application.name:},%X{trace_id:-},%X{span_id:-}]"
```

Cada senal se exporta a un endpoint diferente del mismo puerto 4318, pero con paths distintos:

| Senal | Endpoint OTLP | Backend destino |
|---|---|---|
| Trazas | `http://localhost:4318/v1/traces` | Tempo |
| Logs | `http://localhost:4318/v1/logs` | Loki |
| Metricas | `http://localhost:4318/v1/metrics` | Prometheus |

**`sampling.probability: 1.0`**: En desarrollo se captura el 100% de las trazas. En produccion se baja (0.1 = 10%) para no saturar el backend de observabilidad con millones de trazas.

---

## 5. Correlacion de Logs con Trace ID

### Concepto

Por defecto, los logs de Spring Boot se ven asi:

```
INFO [order-service] Colocando nuevo pedido
INFO [inventory-service] Evento recibido en Inventario para Orden: abc-123
```

No hay forma de saber que estos dos logs pertenecen a la misma peticion. Con la correlacion de Trace ID, se ven asi:

```
INFO [order-service,6a3f2b1c9d4e5f,8b7c6d5e4f3a2b] Colocando nuevo pedido
INFO [inventory-service,6a3f2b1c9d4e5f,1a2b3c4d5e6f7g] Evento recibido en Inventario para Orden: abc-123
```

El `6a3f2b1c9d4e5f` es el **Trace ID** — identico en ambos servicios. Es el hilo conductor que conecta todos los logs de una misma peticion.

### 5.1 Configuracion del patron de logging

```yaml
logging:
  pattern:
    level: "%5p [${spring.application.name:},%X{trace_id:-},%X{span_id:-}]"
```

| Parte del patron | Que imprime | Ejemplo |
|---|---|---|
| `%5p` | Nivel de log (INFO, WARN, ERROR) | `INFO` |
| `${spring.application.name:}` | Nombre del servicio | `order-service` |
| `%X{trace_id:-}` | Trace ID del MDC (inyectado por OTel) | `6a3f2b1c9d4e5f` |
| `%X{span_id:-}` | Span ID del MDC | `8b7c6d5e4f3a2b` |

**MDC (Mapped Diagnostic Context):** OpenTelemetry inyecta automaticamente el `trace_id` y `span_id` en el MDC de SLF4J. El patron de logging los lee y los imprime en cada linea. Esto funciona tanto en la consola local como en los logs exportados a Loki.

---

## 6. El Puente Logback-OpenTelemetry

### El problema

Spring Boot usa **Logback** como framework de logging. OpenTelemetry tiene su propio sistema de exportacion. Por defecto, estos dos mundos no se hablan — Logback imprime a consola y OTel exporta trazas y metricas, pero los **logs** no se exportan.

### La solucion: `logback-spring.xml` + `InstallOpenTelemetryAppender`

Se necesitan dos piezas para conectar Logback con OTLP:

#### 6.1 El archivo `logback-spring.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/base.xml"/>

    <!-- Appender que redirige logs a OpenTelemetry -->
    <appender name="OTEL"
              class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender"/>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>   <!-- Sigue imprimiendo en consola -->
        <appender-ref ref="OTEL"/>       <!-- TAMBIEN envia a OTLP → Loki -->
    </root>
</configuration>
```

Logback soporta multiples appenders. Cada log pasa por **ambos**: se imprime en la consola (para desarrollo) y se exporta via OTLP a Loki (para Grafana). Los niveles de log siguen la misma escalera:

| Nivel | Significado |
|---|---|
| `ERROR` | Fallos criticos que detienen una operacion |
| `WARN` | Problemas que no detienen la app pero hay que revisar |
| `INFO` | Eventos importantes — el estandar para produccion |
| `DEBUG` | Detalles tecnicos para desarrollo |
| `TRACE` | El paso a paso mas detallado (muy ruidoso) |

#### 6.2 El bean `InstallOpenTelemetryAppender`

```java
@Component
class InstallOpenTelemetryAppender implements InitializingBean {
    private final OpenTelemetry openTelemetry;

    InstallOpenTelemetryAppender(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        OpenTelemetryAppender.install(this.openTelemetry);
    }
}
```

**Por que es necesario:** El appender de Logback se registra en el XML, pero necesita la instancia de `OpenTelemetry` que Spring autoconfigura. Este bean implementa `InitializingBean` para que, una vez que Spring termine de crear todos los beans (incluyendo el `OpenTelemetry`), se llame a `OpenTelemetryAppender.install()` para conectar ambos mundos.

Sin este bean, el appender OTEL esta declarado pero no tiene referencia al SDK de OpenTelemetry y los logs no se exportan.

Este componente se repite identico en cada servicio (solo cambia el paquete):
- `order-service/.../config/InstallOpenTelemetryAppender.java`
- `inventory-service/.../config/InstallOpenTelemetryAppender.java`
- `api-gateway/.../config/InstallOpenTelemetryAppender.java`

---

## 7. Analisis Visual en Grafana

### 7.1 Acceso a Grafana

Grafana esta disponible en `http://localhost:3000` una vez que el contenedor `grafana-lgtm` esta levantado. No requiere credenciales en la imagen `otel-lgtm` de desarrollo.

### 7.2 Exploracion de datos

Grafana ofrece la vista **Explore** donde se puede consultar cada fuente de datos:

| Fuente | Que permite | Como acceder |
|---|---|---|
| **Loki** | Buscar logs por servicio, nivel, Trace ID, texto libre | Explore → Loki → `{service_name="order-service"}` |
| **Tempo** | Buscar trazas por Trace ID, servicio, duracion, status | Explore → Tempo → buscar por Trace ID |
| **Prometheus** | Consultar metricas con PromQL | Explore → Prometheus → `http_server_requests_seconds_count` |

### 7.3 El flujo de diagnostico

Cuando se detecta un problema en produccion, el flujo de diagnostico cruza las tres senales:

```
1. METRICAS (Prometheus)
   "La latencia del p95 subio de 200ms a 3s en order-service"
        │
        ▼
2. TRAZAS (Tempo)
   "La traza abc-123 muestra que el span 'inventory-queue' tardo 2.8s"
        │
        ▼
3. LOGS (Loki)
   Filtrar por trace_id=abc-123 → "Stock insuficiente para SKU iphone-15"
        │
        ▼
4. CAUSA RAIZ
   El producto no tenia stock y Inventory tardo en responder
   porque la query a MySQL estaba haciendo full table scan
```

### 7.4 Correlacion entre senales

La magia de OpenTelemetry es que las tres senales comparten el mismo **Trace ID**. En Grafana:

- Desde un **log** en Loki, se puede hacer clic en el Trace ID para saltar directamente a la traza en Tempo
- Desde una **traza** en Tempo, se pueden ver los logs asociados a cada span
- Desde una **metrica** en Prometheus, se pueden ver las trazas que contribuyeron a un spike de latencia

Esto es posible porque OpenTelemetry inyecta el mismo Trace ID en los tres flujos de datos desde el origen.

---

## Archivos clave de esta seccion

| Archivo | Que hace |
|---|---|
| `docker-compose.yml` | Agrega servicio `grafana-lgtm` con puertos 3000 (UI), 4317 (gRPC), 4318 (HTTP OTLP) |
| `config-data/order-service.yml` | Config OTLP: endpoints de trazas, logs y metricas + patron de logging con Trace ID |
| `config-data/inventory-service.yml` | Misma config OTLP que order-service |
| `config-data/api-gateway.yml` | Misma config OTLP + patron de logging con correlacion |
| `order-service/pom.xml` | Dependencias: `spring-boot-starter-opentelemetry` + `opentelemetry-logback-appender-1.0` |
| `inventory-service/pom.xml` | Mismas dependencias OTel |
| `api-gateway/pom.xml` | Mismas dependencias OTel |
| `order-service/.../config/InstallOpenTelemetryAppender.java` | Bean que conecta Logback con el SDK de OpenTelemetry via `InitializingBean` |
| `inventory-service/.../config/InstallOpenTelemetryAppender.java` | Mismo bean (distinto paquete) |
| `api-gateway/.../config/InstallOpenTelemetryAppender.java` | Mismo bean (distinto paquete) |
| `order-service/src/main/resources/logback-spring.xml` | Configura appenders CONSOLE + OTEL para enviar logs a Loki |
| `inventory-service/src/main/resources/logback-spring.xml` | Misma config de appenders |
| `api-gateway/src/main/resources/logback-spring.xml` | Misma config de appenders |
