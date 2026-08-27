FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
RUN --mount=type=cache,id=cowork-project-maven,target=/root/.m2,sharing=locked \
    ./mvnw -B -DskipTests clean package \
    && artifact="$(find target -maxdepth 1 -type f -name 'cowork-project-*.jar' -print -quit)" \
    && test -n "${artifact}" \
    && cp "${artifact}" /workspace/app.jar

FROM eclipse-temurin:25-jre-alpine AS runtime
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --chown=app:app --from=builder /workspace/app.jar app.jar
USER app
EXPOSE 8084
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
