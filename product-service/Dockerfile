# ETAPA 1: Usar una imagen base de Maven para construir la aplicación
FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# ETAPA 2: Usar una imagen base de Java para ejecutar la aplicación
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar product.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "product.jar"]