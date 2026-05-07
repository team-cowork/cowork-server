FROM eclipse-temurin:21-jdk-alpine AS builder
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
COPY cowork-preference/src cowork-preference/src
RUN chmod +x gradlew && ./gradlew :cowork-preference:installDist -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=builder /workspace/cowork-preference/build/install/cowork-preference .
EXPOSE 9001
ENTRYPOINT ["./bin/cowork-preference"]
