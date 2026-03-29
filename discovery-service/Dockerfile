FROM eclipse-temurin:17-jdk
WORKDIR /app

COPY target/testProj-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENV REGISTRY_FILE_PATH=/app/data/registry.json

ENTRYPOINT ["java", "-jar", "app.jar"]