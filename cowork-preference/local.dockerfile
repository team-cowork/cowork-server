# Build stage: compile and package with the Kotlin Toolchain (Amper) CLI.
# A glibc-based image is used because the Kotlin CLI launcher is not musl-compatible.
FROM eclipse-temurin:21-jdk AS builder
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL https://kotl.in/install.sh | KOTLIN_CLI_NO_MODIFY_PATH=1 sh
ENV PATH="/root/.local/bin:$PATH"
WORKDIR /workspace/cowork-preference
COPY cowork-preference/module.yaml module.yaml
COPY cowork-preference/src src
RUN kotlin package

# Runtime stage: the executable JAR is a plain JVM artifact, so a small alpine JRE is enough.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=builder /workspace/cowork-preference/build/tasks/_cowork-preference_executableJarJvm/cowork-preference-jvm-executable.jar app.jar
EXPOSE 9001
ENTRYPOINT ["java", "-jar", "app.jar"]