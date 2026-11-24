# syntax=docker/dockerfile:1
# Multi-stage build: JDK to build the jar, JRE-only runtime as a non-root user.
# The frontend (node) stage is added in milestone 6, when frontend/ exists (see docs/DECISIONS.md).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Copy build scripts first so dependency resolution is cached separately from sources.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY backend/build.gradle.kts backend/
RUN chmod +x gradlew && ./gradlew :backend:dependencies --no-daemon -q > /dev/null
COPY backend/src backend/src
RUN ./gradlew :backend:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 tracker
COPY --from=build /src/backend/build/libs/llm-cost-tracker.jar /app/app.jar
USER tracker
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
