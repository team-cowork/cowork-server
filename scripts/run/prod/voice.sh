#!/bin/bash
# cowork-voice 배포 스크립트 (전용 VM, cowork-server_default 네트워크 밖 — 내부 IP 10.0.0.93으로 공유 인프라 접근)
set -euo pipefail

: "${DEPLOY_IMAGE_OWNER:?DEPLOY_IMAGE_OWNER is required}"
: "${DEPLOY_IMAGE_TAG:?DEPLOY_IMAGE_TAG is required}"
: "${COWORK_MONGO_PASSWORD:?COWORK_MONGO_PASSWORD is required}"
: "${LIVEKIT_API_KEY:?LIVEKIT_API_KEY is required}"
: "${LIVEKIT_API_SECRET:?LIVEKIT_API_SECRET is required}"

INFRA_HOST="10.0.0.93"
CONTAINER="cowork-voice"
IMAGE="ghcr.io/${DEPLOY_IMAGE_OWNER}/cowork-voice:${DEPLOY_IMAGE_TAG}"
PORT=8089
SELF_IP="$(hostname -I | awk '{print $1}')"

echo "[voice] pulling ${IMAGE}"
docker pull "${IMAGE}"

echo "[voice] removing existing container (if any)"
docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true

echo "[voice] starting new container"
docker run -d --name "${CONTAINER}" --restart unless-stopped \
  -p ${PORT}:${PORT} \
  -e APP_CONFIG_URL="http://${INFRA_HOST}:8761" \
  -e APP_PROFILE=local \
  -e PORT="${PORT}" \
  -e KAFKA_BROKERS="${INFRA_HOST}:9094" \
  -e MONGODB_DB=cowork_voice \
  -e MONGODB_URI="mongodb://root:${COWORK_MONGO_PASSWORD}@${INFRA_HOST}:27017/cowork_voice?authSource=admin" \
  -e REDIS_ADDR="${INFRA_HOST}:6379" \
  -e EUREKA_SERVER_URL="http://${INFRA_HOST}:8761/eureka" \
  -e EUREKA_INSTANCE_HOST="${SELF_IP}" \
  -e LIVEKIT_URL="http://${INFRA_HOST}:7880" \
  -e LIVEKIT_WS_URL="ws://141.164.42.34:7880" \
  -e LIVEKIT_API_KEY="${LIVEKIT_API_KEY}" \
  -e LIVEKIT_API_SECRET="${LIVEKIT_API_SECRET}" \
  -e CHANNEL_SERVICE_URL="http://10.0.0.97:8083" \
  "${IMAGE}"

echo "[voice] waiting for health check"
for _ in $(seq 1 45); do
  if curl -sf "http://127.0.0.1:${PORT}/health/ready" >/dev/null; then
    echo "[voice] healthy"
    exit 0
  fi
  sleep 2
done

echo "[voice] FAILED health check after deploy" >&2
docker logs --tail 50 "${CONTAINER}" >&2 || true
exit 1
