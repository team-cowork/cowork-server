# 빌드 전 cowork-authorization (CGO_ENABLED=0 GOOS=linux 정적 바이너리)이 컨텍스트에 있어야 한다.
# TODO: CI 산출물 핸드오프 배선 후 이 주석 삭제
FROM alpine:3.20
RUN apk --no-cache add ca-certificates tzdata && \
    addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --chown=app:app cowork-authorization /usr/local/bin/cowork-authorization
COPY --chown=app:app src/main/resources/db/migration ./db/migration
USER app
EXPOSE 8081
CMD ["cowork-authorization"]
