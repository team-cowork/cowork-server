#!/bin/bash
# cowork-chat 배포 스크립트 (전용 VM, cowork-server_default 네트워크 밖 — 내부 IP 10.0.0.93으로 공유 인프라 접근)
#
# NestJS 서비스. MongoDB 하나만 쓰는 다른 서비스들과 달리 Elasticsearch/S3(MinIO)/Redis까지
# 직접 붙는다. 다른 서비스 호출은 Eureka 대신 하드코딩된 *_SERVICE_URL로 이뤄진다(기존
# 서버-로컬 스크립트의 동작을 그대로 유지).
set -euo pipefail

: "${DEPLOY_IMAGE_OWNER:?DEPLOY_IMAGE_OWNER is required}"
: "${DEPLOY_IMAGE_TAG:?DEPLOY_IMAGE_TAG is required}"
: "${COWORK_MONGO_PASSWORD:?COWORK_MONGO_PASSWORD is required}"
: "${JWT_SECRET:?JWT_SECRET is required}"

INFRA_HOST="10.0.0.93"
CONTAINER="cowork-chat"
IMAGE="ghcr.io/${DEPLOY_IMAGE_OWNER}/cowork-chat:${DEPLOY_IMAGE_TAG}"
PORT=8087
SELF_IP="$(hostname -I | awk '{print $1}')"

echo "[chat] pulling ${IMAGE}"
docker pull "${IMAGE}"

echo "[chat] removing existing container (if any)"
docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true

echo "[chat] starting new container"
docker run -d --name "${CONTAINER}" --restart unless-stopped \
  -p ${PORT}:${PORT} \
  -e APP_CONFIG_URL="http://${INFRA_HOST}:8761" \
  -e APP_PROFILE=local \
  -e PORT="${PORT}" \
  -e JWT_SECRET="${JWT_SECRET}" \
  -e MONGODB_URI="mongodb://root:${COWORK_MONGO_PASSWORD}@${INFRA_HOST}:27017/cowork_chat?authSource=admin" \
  -e KAFKA_BOOTSTRAP_SERVERS="${INFRA_HOST}:9094" \
  -e REDIS_HOST="${INFRA_HOST}" \
  -e ELASTICSEARCH_URL="http://${INFRA_HOST}:9200" \
  -e S3_INTERNAL_ENDPOINT="http://${INFRA_HOST}:9000" \
  -e S3_PUBLIC_ENDPOINT=http://ssh.gsmsv.site:22108 \
  -e S3_PUBLIC_BASE_URL=http://ssh.gsmsv.site:22108/cowork-bucket \
  -e S3_ACCESS_KEY=minioadmin \
  -e S3_SECRET_KEY=minioadmin \
  -e S3_BUCKET=cowork-bucket \
  -e EUREKA_SERVER_URL="http://${INFRA_HOST}:8761/eureka" \
  -e EUREKA_INSTANCE_HOST="${SELF_IP}" \
  -e USER_SERVICE_URL="http://10.0.0.144:8082" \
  -e CHANNEL_SERVICE_URL="http://10.0.0.97:8083" \
  -e PROJECT_SERVICE_URL="http://10.0.0.145:8089" \
  "${IMAGE}"

echo "[chat] waiting for health check"
# /health/ready는 Kafka 프로젝션 상태까지 반영하는데, 알려진 별도 이슈(레거시 키
# 포맷의 channel.member.event 재구축 불가, chat_membership_요약.md 참고)로 인해
# 정상 기동 중에도 계속 503을 낼 수 있다. 배포 성공 여부는 기본 liveness인
# /health로 판단한다.
for _ in $(seq 1 45); do
  if curl -sf "http://127.0.0.1:${PORT}/health" >/dev/null; then
    echo "[chat] healthy"
    exit 0
  fi
  sleep 2
done

echo "[chat] FAILED health check after deploy" >&2
docker logs --tail 50 "${CONTAINER}" >&2 || true
exit 1
