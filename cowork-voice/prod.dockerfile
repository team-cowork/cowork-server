# 빌드 전 cowork-voice (CGO_ENABLED=0 GOOS=linux 정적 바이너리)이 컨텍스트에 있어야 한다.
# TODO: CI 산출물 핸드오프 배선 후 이 주석 삭제
FROM alpine:3.20
RUN apk --no-cache add ca-certificates tzdata && \
    addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --chown=app:app cowork-voice /usr/local/bin/cowork-voice
USER app
EXPOSE 8084
ENV PORT=8084
CMD ["cowork-voice"]
