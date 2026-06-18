# 빌드 전 build/tasks/_cowork-preference_executableJarJvm/cowork-preference-jvm-executable.jar
# (kotlin package) 산출물이 컨텍스트에 있어야 한다.
# TODO: CI 산출물 핸드오프 배선 후 이 주석 삭제
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --chown=app:app build/tasks/_cowork-preference_executableJarJvm/cowork-preference-jvm-executable.jar app.jar
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENV PREFERENCE_LOG_DIR=/var/log/cowork/preference
RUN mkdir -p /var/log/cowork/preference && chown -R app:app /var/log/cowork
USER app
EXPOSE 9001
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
