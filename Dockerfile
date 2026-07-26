# ── Build: compila el jar dentro de la imagen (no depende de target/ del host) ──
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

# ── Runtime: JRE mínimo + curl para el healthcheck ──
FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 1001 spring
USER spring
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 9095
ENTRYPOINT ["java", "-jar", "app.jar"]
