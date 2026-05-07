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
COPY cowork-team/src cowork-team/src
COPY cowork-preference/build.gradle.kts cowork-preference/build.gradle.kts
RUN chmod +x gradlew && ./gradlew :cowork-team:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=builder /workspace/cowork-team/build/libs/*.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
