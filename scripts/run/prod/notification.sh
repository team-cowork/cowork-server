#!/bin/bash
# cowork-notification 배포 스크립트 (전용 VM, cowork-server_default 네트워크 밖 — 내부 IP 10.0.0.93으로 공유 인프라 접근)
#
# FCM 서비스 계정 JSON은 GitHub Secret으로 관리하지 않는다(파일이라 시크릿 값으로 넣기
# 부적합) — 이 VM의 /home/ubuntu/notification-secrets/firebase-credentials.json에
# 미리 배치돼 있어야 하며, 없으면 배포를 실패시킨다.
set -euo pipefail

: "${DEPLOY_IMAGE_OWNER:?DEPLOY_IMAGE_OWNER is required}"
: "${DEPLOY_IMAGE_TAG:?DEPLOY_IMAGE_TAG is required}"
: "${COWORK_MYSQL_PASSWORD:?COWORK_MYSQL_PASSWORD is required}"

INFRA_HOST="10.0.0.93"
CONTAINER="cowork-notification"
IMAGE="ghcr.io/${DEPLOY_IMAGE_OWNER}/cowork-notification:${DEPLOY_IMAGE_TAG}"
PORT=8086
FIREBASE_CREDENTIALS="$HOME/notification-secrets/firebase-credentials.json"
SELF_IP="$(hostname -I | awk '{print $1}')"

if [ ! -f "${FIREBASE_CREDENTIALS}" ]; then
  echo "[notification] FAILED: ${FIREBASE_CREDENTIALS} not found on this VM — Firebase 서비스 계정 JSON을 먼저 배치해주세요" >&2
  exit 1
fi

echo "[notification] pulling ${IMAGE}"
docker pull "${IMAGE}"

echo "[notification] removing existing container (if any)"
docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true

echo "[notification] starting new container"
docker run -d --name "${CONTAINER}" --restart unless-stopped \
  -p ${PORT}:${PORT} \
  -v "${FIREBASE_CREDENTIALS}:/run/secrets/firebase-credentials.json:ro" \
  -e APP_CONFIG_URL="http://${INFRA_HOST}:8761" \
  -e APP_PROFILE=local \
  -e PORT="${PORT}" \
  -e FCM_CREDENTIALS_FILE=/run/secrets/firebase-credentials.json \
  -e DB_DSN="cowork:${COWORK_MYSQL_PASSWORD}@tcp(${INFRA_HOST}:3306)/cowork_notification?charset=utf8mb4&parseTime=True&loc=Local" \
  -e KAFKA_BROKERS="${INFRA_HOST}:9094" \
  -e KAFKA_TOPIC_NOTIFICATION=notification.trigger \
  -e KAFKA_GROUP_ID=cowork-notification \
  -e EUREKA_SERVER_URL="http://${INFRA_HOST}:8761/eureka" \
  -e EUREKA_INSTANCE_HOST="${SELF_IP}" \
  -e TEAM_SERVICE_URL="http://${INFRA_HOST}:8085" \
  -e USER_SERVICE_URL="http://10.0.0.144:8082" \
  -e PREFERENCE_SERVICE_URL="http://10.0.0.167:9001" \
  "${IMAGE}"

echo "[notification] waiting for health check"
for _ in $(seq 1 45); do
  if curl -sf "http://127.0.0.1:${PORT}/health/ready" >/dev/null; then
    echo "[notification] healthy"
    exit 0
  fi
  sleep 2
done

echo "[notification] FAILED health check after deploy" >&2
docker logs --tail 50 "${CONTAINER}" >&2 || true
exit 1
