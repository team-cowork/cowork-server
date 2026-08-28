FROM eclipse-temurin:25-jdk-jammy AS builder
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ARG KOTLIN_CLI_VERSION=0.11.0
ARG KOTLIN_CLI_WRAPPER_SHA256=f53d26ee0bb6ef3078c33bda3c180d19fbca665408c27b62b7dfce335551c3d7
RUN curl -fsSL -o /usr/local/bin/kotlin \
      "https://packages.jetbrains.team/maven/p/amper/amper/org/jetbrains/kotlin/kotlin-cli/${KOTLIN_CLI_VERSION}/kotlin-cli-${KOTLIN_CLI_VERSION}-wrapper" \
    && echo "${KOTLIN_CLI_WRAPPER_SHA256}  /usr/local/bin/kotlin" | sha256sum -c - \
    && chmod +x /usr/local/bin/kotlin \
    && kotlin --version

WORKDIR /workspace/cowork-preference
COPY module.yaml module.yaml
COPY src src
RUN kotlin package

FROM eclipse-temurin:25-jre-alpine AS runtime
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --chown=app:app --from=builder /workspace/cowork-preference/build/tasks/_cowork-preference_executableJarJvm/cowork-preference-jvm-executable.jar app.jar
ENV PREFERENCE_LOG_DIR=/var/log/cowork/preference
RUN mkdir -p /var/log/cowork/preference && chown -R app:app /var/log/cowork
USER app
EXPOSE 9001
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
