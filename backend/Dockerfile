# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the POM alone first so the dependency layer is cached and only
# invalidated when dependencies actually change.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-noble
WORKDIR /app

# Non-root. The container has no reason to write anywhere but /tmp.
RUN groupadd --system --gid 1001 app \
 && useradd --system --uid 1001 --gid app app

COPY --from=build --chown=app:app /build/target/*.jar app.jar

USER app
EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx so the heap tracks whatever the
# container is actually given.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
