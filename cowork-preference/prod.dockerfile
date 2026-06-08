# Build stage: compile and package with the Kotlin Toolchain (Amper) CLI.
# A glibc-based image is used because the Kotlin CLI launcher is not musl-compatible.
FROM eclipse-temurin:21-jdk AS builder
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
# Pinned, checksum-verified Kotlin Toolchain (Amper) CLI wrapper for reproducible builds.
ARG KOTLIN_CLI_VERSION=0.11.0
ARG KOTLIN_CLI_WRAPPER_SHA256=f53d26ee0bb6ef3078c33bda3c180d19fbca665408c27b62b7dfce335551c3d7
RUN curl -fsSL -o /usr/local/bin/kotlin \
      "https://packages.jetbrains.team/maven/p/amper/amper/org/jetbrains/kotlin/kotlin-cli/${KOTLIN_CLI_VERSION}/kotlin-cli-${KOTLIN_CLI_VERSION}-wrapper" \
    && echo "${KOTLIN_CLI_WRAPPER_SHA256}  /usr/local/bin/kotlin" | sha256sum -c - \
    && chmod +x /usr/local/bin/kotlin
WORKDIR /workspace/cowork-preference
COPY cowork-preference/module.yaml module.yaml
COPY cowork-preference/src src
RUN kotlin package

# Runtime stage: the executable JAR is a plain JVM artifact, so a small alpine JRE is enough.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --chown=app:app --from=builder /workspace/cowork-preference/build/tasks/_cowork-preference_executableJarJvm/cowork-preference-jvm-executable.jar app.jar
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
# Write logs to a writable, volume-friendly path owned by the non-root user.
ENV PREFERENCE_LOG_DIR=/var/log/cowork/preference
RUN mkdir -p /var/log/cowork/preference && chown -R app:app /var/log/cowork
USER app
EXPOSE 9001
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]