# 빌드 전 build/libs/app.jar (CI가 :cowork-team:bootJar 산출물을 app.jar로 전달)이 컨텍스트에 있어야 한다.
# TODO: CI 산출물 핸드오프 배선 후 이 주석 삭제
FROM eclipse-temurin:21-jre-alpine AS extractor
WORKDIR /app
COPY build/libs/app.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract --destination extracted

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --chown=app:app --from=extractor /app/extracted/dependencies ./
COPY --chown=app:app --from=extractor /app/extracted/spring-boot-loader ./
COPY --chown=app:app --from=extractor /app/extracted/snapshot-dependencies ./
COPY --chown=app:app --from=extractor /app/extracted/application ./
USER app
EXPOSE 8085
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "org.springframework.boot.loader.launch.JarLauncher"]
