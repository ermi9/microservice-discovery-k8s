FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/eda-api-gateway-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
