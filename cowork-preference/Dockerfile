FROM eclipse-temurin:26.0.2.1_1-jdk-noble@sha256:9c0e8eddecccc0d5679b317b0c90fa7103ad25cae33df1c00280675eb681c7f8 AS builder
RUN sed -i 's|http://|https://|g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get -o Acquire::Retries=3 update \
    && apt-get -o Acquire::Retries=3 install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace/cowork-preference
COPY --chmod=0755 cowork-preference/kotlin kotlin
COPY cowork-preference/module.yaml cowork-preference/project.yaml ./
RUN --mount=type=cache,id=cowork-preference-toolchain,target=/root/.cache,sharing=locked \
    ./kotlin --version

COPY cowork-preference/build-plugins build-plugins
COPY cowork-preference/src src
RUN --mount=type=cache,id=cowork-preference-toolchain,target=/root/.cache,sharing=locked \
    ./kotlin package


FROM builder AS verification
RUN apt-get update && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/*
COPY .editorconfig /workspace/.editorconfig
COPY deploy/ci/verification /ci
RUN --mount=type=cache,id=cowork-preference-toolchain,target=/root/.cache,sharing=locked \
    ./kotlin check ktlint \
    && bash /ci/run-jvm.sh kotlin-toolchain cowork-preference /workspace /coverage \
    && python3 /ci/report.py collect --module cowork-preference --snapshot pr --format jacoco --metric line --input /coverage/report.xml --output /coverage/coverage.json --exit-code 0

FROM scratch AS verify
COPY --from=verification /coverage /

FROM eclipse-temurin:26.0.2.1_1-jre-alpine-3.24@sha256:158186ab168dc35e5a8e799f6195eef61d57a86a73348fc3a1188317fc40f01c AS runtime
RUN addgroup -S -g 10001 app && adduser -S -u 10001 app -G app
WORKDIR /app
COPY --chown=app:app --from=builder /workspace/cowork-preference/build/tasks/_cowork-preference_executableJarJvm/cowork-preference-jvm-executable.jar app.jar
ENV PREFERENCE_LOG_DIR=/var/log/cowork/preference
RUN mkdir -p /var/log/cowork/preference && chown -R app:app /var/log/cowork
RUN test "$(stat -c %u /var/log/cowork)" = 10001
USER app
EXPOSE 9001
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
