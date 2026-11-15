# Build stage
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests

# Runtime stage: JRE only, non-root
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 gateway && mkdir -p /app/data && chown 10001 /app/data  # H2 database file
WORKDIR /app
COPY --from=build /src/target/multi-tenant-api-gateway-*.jar app.jar
USER 10001
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
