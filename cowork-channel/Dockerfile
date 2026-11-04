FROM eclipse-temurin:26.0.2_10-jdk-alpine-3.24@sha256:e5b0a876436e5d1c8ff84dfd091cb530b83a9ed4273317d24acb673695bb7e0c AS builder
WORKDIR /workspace/cowork-channel

COPY cowork-channel/gradlew gradlew
COPY cowork-channel/gradle gradle
COPY cowork-channel/settings.gradle.kts cowork-channel/build.gradle.kts ./
COPY cowork-channel/src src
RUN --mount=type=cache,id=cowork-gradle,target=/root/.gradle,sharing=locked \
    chmod +x gradlew \
    && ./gradlew bootJar -x test --no-daemon --build-cache \
    && artifact="$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" \
    && test -n "${artifact}" \
    && cp "${artifact}" /workspace/app.jar

RUN java -Djarmode=tools -jar /workspace/app.jar extract \
    --layers --launcher --destination /workspace/extracted


FROM builder AS verification
RUN apk --no-cache add bash python3 curl
COPY .editorconfig /workspace/.editorconfig
COPY deploy/ci/verification /ci
RUN --mount=type=cache,id=cowork-gradle,target=/root/.gradle,sharing=locked \
    ./gradlew check -x test --no-daemon --build-cache \
    && bash /ci/run-jvm.sh gradle cowork-channel /workspace /coverage \
    && python3 /ci/report.py collect --module cowork-channel --snapshot pr --format jacoco --metric line --input /coverage/report.xml --output /coverage/coverage.json --exit-code 0

FROM scratch AS verify
COPY --from=verification /coverage /

FROM eclipse-temurin:26.0.2_10-jre-alpine-3.24@sha256:2c984601c59ac93f97947ce66d03df25c2e16bed97a28289fbc0ab157087f1b5 AS runtime
RUN addgroup -S -g 10001 app && adduser -S -u 10001 app -G app
WORKDIR /app
COPY --chown=app:app --from=builder /workspace/extracted/dependencies/ ./
COPY --chown=app:app --from=builder /workspace/extracted/spring-boot-loader/ ./
COPY --chown=app:app --from=builder /workspace/extracted/snapshot-dependencies/ ./
COPY --chown=app:app --from=builder /workspace/extracted/application/ ./
ENV COWORK_LOG_DIR=/var/log/cowork
RUN mkdir -p /var/log/cowork && chown app:app /var/log/cowork
RUN test "$(stat -c %u /var/log/cowork)" = 10001
USER app
EXPOSE 8083
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "org.springframework.boot.loader.launch.JarLauncher"]
