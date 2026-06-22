#!/usr/bin/env bash
set -euo pipefail

# 학교 서버에서 SSH 접속 후 이 스크립트를 실행해 배포합니다.
# Kafka/Redis는 이 서버가 아닌 별도 서버(10.0.0.93)에서 동작하므로,
# 컨테이너 네트워크 연결 없이 .env의 KAFKA_BROKERS/REDIS_HOST로 접속합니다.
APP_NAME="cowork-github-app"
APP_PORT="${APP_PORT:-3000}"
ENV_FILE="${ENV_FILE:-.env}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "$ENV_FILE 파일이 없습니다. 운영용 .env를 먼저 서버에 준비해주세요."
  exit 1
fi

echo "==> 최신 main 가져오기"
git checkout main
git pull origin main

echo "==> 이미지 빌드"
docker build -t "$APP_NAME:latest" .

echo "==> 기존 컨테이너 정리"
docker stop "$APP_NAME" 2>/dev/null || true
docker rm "$APP_NAME" 2>/dev/null || true

echo "==> 컨테이너 기동"
docker run -d \
  --name "$APP_NAME" \
  --env-file "$ENV_FILE" \
  -p "$APP_PORT:3000" \
  --restart unless-stopped \
  "$APP_NAME:latest"

echo "==> 헬스체크 시작 (최대 15초 대기)"
success=false
for i in {1..15}; do
  if curl -fsS "http://localhost:$APP_PORT/health" >/dev/null 2>&1; then
    success=true
    break
  fi
  sleep 1
done

if [[ "$success" == "true" ]]; then
  echo " - OK"
else
  echo " - 실패, docker logs $APP_NAME 확인 필요"
  exit 1
fi
