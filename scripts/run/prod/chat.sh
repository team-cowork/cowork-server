#!/bin/bash
# cowork-chat 배포 스크립트 (전용 VM, cowork-server_default 네트워크 밖 — 내부 IP 10.0.0.93으로 공유 인프라 접근)
#
# NestJS 서비스. MongoDB 하나만 쓰는 다른 서비스들과 달리 Elasticsearch/S3(MinIO)/Redis까지
# 직접 붙는다. 다른 서비스 호출은 Eureka 대신 하드코딩된 *_SERVICE_URL로 이뤄진다(기존
# 서버-로컬 스크립트의 동작을 그대로 유지).
#
# 새 이미지를 임시 컨테이너로 먼저 헬스체크하고 통과했을 때만 기존 컨테이너를 교체한다
# (scripts/run/prod/_lib.sh의 deploy_container_safely) — 실패해도 서비스가 내려가지 않는다.
# 헬스체크는 /health(liveness)로 판단한다 — /health/ready는 알려진 별도 이슈(레거시 키
# 포맷의 channel.member.event 재구축 불가, chat_membership_요약.md 참고)로 정상 기동
# 중에도 503을 낼 수 있다.
set -euo pipefail

: "${DEPLOY_IMAGE_OWNER:?DEPLOY_IMAGE_OWNER is required}"
: "${DEPLOY_IMAGE_TAG:?DEPLOY_IMAGE_TAG is required}"
: "${COWORK_MONGO_PASSWORD:?COWORK_MONGO_PASSWORD is required}"
: "${JWT_SECRET:?JWT_SECRET is required}"

# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

INFRA_HOST="10.0.0.93"
CONTAINER="cowork-chat"
IMAGE="ghcr.io/${DEPLOY_IMAGE_OWNER}/cowork-chat:${DEPLOY_IMAGE_TAG}"
PORT=8087
SELF_IP="$(advertise_ip "${INFRA_HOST}")"

ghcr_login_if_needed

echo "[chat] pulling ${IMAGE}"
docker pull "${IMAGE}"

deploy_container_safely "${CONTAINER}" "${PORT}" "${PORT}" "/health" "${IMAGE}" -- \
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
  -e PROJECT_SERVICE_URL="http://10.0.0.145:8089"
