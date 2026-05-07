FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY cowork-config/build.gradle.kts cowork-config/build.gradle.kts
COPY cowork-gateway/build.gradle.kts cowork-gateway/build.gradle.kts
COPY cowork-gateway/src cowork-gateway/src
COPY cowork-channel/build.gradle.kts cowork-channel/build.gradle.kts
COPY cowork-project/build.gradle.kts cowork-project/build.gradle.kts
COPY cowork-team/build.gradle.kts cowork-team/build.gradle.kts
COPY cowork-preference/build.gradle.kts cowork-preference/build.gradle.kts
RUN chmod +x gradlew && ./gradlew :cowork-gateway:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine AS extractor
WORKDIR /app
COPY --from=builder /workspace/cowork-gateway/build/libs/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract --destination extracted

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=extractor /app/extracted/dependencies ./
COPY --from=extractor /app/extracted/spring-boot-loader ./
COPY --from=extractor /app/extracted/snapshot-dependencies ./
COPY --from=extractor /app/extracted/application ./
USER app
EXPOSE 8080
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "org.springframework.boot.loader.launch.JarLauncher"]
