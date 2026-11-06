# syntax=docker/dockerfile:1
FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY --from=builder /app/target/vigo-notification-service.jar app.jar

LABEL logging="true" service="notification"
ENV LOG_VECTOR_ENABLED=false
ENV LOG_CONSOLE_FORMAT=json
ENV SPRING_MAIN_BANNER_MODE=off
USER 10001:10001
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
