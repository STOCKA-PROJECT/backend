FROM eclipse-temurin:25-jdk
WORKDIR /app
COPY target/backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 9095
ENTRYPOINT ["java", "-jar", "app.jar"]