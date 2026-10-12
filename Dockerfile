# ---- Build stage ----
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app

# Cache dependencies separately from source so `mvn package` doesn't re-download on every code change
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# Headless JVM: the Excel export (Apache POI) must never try to open a display.
ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true"

RUN addgroup -S spring && adduser -S spring -G spring && mkdir -p /app/uploads && chown spring:spring /app/uploads
COPY --from=build /app/target/*.jar app.jar
USER spring:spring

EXPOSE 8080
# Swagger's API docs are public and answer once the app (and Flyway migrations) are up.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5     CMD wget -qO- http://localhost:8080/v3/api-docs > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
