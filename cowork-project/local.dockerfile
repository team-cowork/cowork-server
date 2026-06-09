# Build stage: compile and package with the bundled Maven wrapper.
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace/cowork-project
# Resolve dependencies first so this layer is cached when only sources change.
COPY cowork-project/.mvn .mvn
COPY cowork-project/mvnw mvnw
COPY cowork-project/pom.xml pom.xml
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline || true
COPY cowork-project/src src
RUN ./mvnw -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --chown=app:app --from=builder /workspace/cowork-project/target/cowork-project-*.jar app.jar
USER app
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]