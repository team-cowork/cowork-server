FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY cowork-config/build.gradle.kts cowork-config/build.gradle.kts
COPY cowork-gateway/build.gradle.kts cowork-gateway/build.gradle.kts
COPY cowork-channel/build.gradle.kts cowork-channel/build.gradle.kts
COPY cowork-project/build.gradle.kts cowork-project/build.gradle.kts
COPY cowork-team/build.gradle.kts cowork-team/build.gradle.kts
COPY cowork-preference/build.gradle.kts cowork-preference/build.gradle.kts
COPY cowork-roadmap/build.gradle.kts cowork-roadmap/build.gradle.kts
COPY cowork-gateway/src cowork-gateway/src
RUN --mount=type=cache,id=cowork-gradle,target=/root/.gradle,sharing=locked \
    chmod +x gradlew \
    && ./gradlew :cowork-gateway:bootJar -x test --no-daemon \
    && artifact="$(find cowork-gateway/build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" \
    && test -n "${artifact}" \
    && cp "${artifact}" /workspace/app.jar

RUN java -Djarmode=tools -jar /workspace/app.jar extract \
    --layers --launcher --destination /workspace/extracted

FROM eclipse-temurin:25-jre-alpine AS runtime
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --chown=app:app --from=builder /workspace/extracted/dependencies/ ./
COPY --chown=app:app --from=builder /workspace/extracted/spring-boot-loader/ ./
COPY --chown=app:app --from=builder /workspace/extracted/snapshot-dependencies/ ./
COPY --chown=app:app --from=builder /workspace/extracted/application/ ./
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "org.springframework.boot.loader.launch.JarLauncher"]
