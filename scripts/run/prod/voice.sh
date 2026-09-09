#!/bin/bash
# cowork-voice 배포 스크립트 (전용 VM, cowork-server_default 네트워크 밖 — 내부 IP 10.0.0.93으로 공유 인프라 접근)
#
# 새 이미지를 임시 컨테이너로 먼저 헬스체크하고 통과했을 때만 기존 컨테이너를 교체한다
# (scripts/run/prod/_lib.sh의 deploy_container_safely) — 실패해도 서비스가 내려가지 않는다.
set -euo pipefail

: "${DEPLOY_IMAGE_OWNER:?DEPLOY_IMAGE_OWNER is required}"
: "${DEPLOY_IMAGE_TAG:?DEPLOY_IMAGE_TAG is required}"
: "${COWORK_MONGO_PASSWORD:?COWORK_MONGO_PASSWORD is required}"
: "${LIVEKIT_API_KEY:?LIVEKIT_API_KEY is required}"
: "${LIVEKIT_API_SECRET:?LIVEKIT_API_SECRET is required}"

# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

INFRA_HOST="10.0.0.93"
CONTAINER="cowork-voice"
IMAGE="ghcr.io/${DEPLOY_IMAGE_OWNER}/cowork-voice:${DEPLOY_IMAGE_TAG}"
PORT=8089
SELF_IP="$(advertise_ip "${INFRA_HOST}")"

ghcr_login_if_needed

echo "[voice] pulling ${IMAGE}"
docker pull "${IMAGE}"

deploy_container_safely "${CONTAINER}" "${PORT}" "${PORT}" "/health/ready" "${IMAGE}" -- \
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
  -e CHANNEL_SERVICE_URL="http://10.0.0.97:8083"
