#!/bin/bash
# cowork-preference 배포 스크립트 (전용 VM, cowork-server_default 네트워크 밖 — 내부 IP 10.0.0.93으로 공유 인프라 접근)
#
# Vert.x/Kotlin 기반이라 Spring Cloud Config를 안 쓰고 자체 CONFIG_SERVER_URL 부트스트랩을
# 쓴다. PostgreSQL을 사용하는 유일한 서비스.
set -euo pipefail

: "${DEPLOY_IMAGE_OWNER:?DEPLOY_IMAGE_OWNER is required}"
: "${DEPLOY_IMAGE_TAG:?DEPLOY_IMAGE_TAG is required}"
: "${COWORK_POSTGRES_PASSWORD:?COWORK_POSTGRES_PASSWORD is required}"

INFRA_HOST="10.0.0.93"
CONTAINER="cowork-preference"
IMAGE="ghcr.io/${DEPLOY_IMAGE_OWNER}/cowork-preference:${DEPLOY_IMAGE_TAG}"
PORT=9001
SELF_IP="$(hostname -I | awk '{print $1}')"

echo "[preference] pulling ${IMAGE}"
docker pull "${IMAGE}"

echo "[preference] removing existing container (if any)"
docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true

echo "[preference] starting new container"
docker run -d --name "${CONTAINER}" --restart unless-stopped \
  -p ${PORT}:${PORT} \
  -e SPRING_PROFILES_ACTIVE=local \
  -e CONFIG_SERVER_URL="http://${INFRA_HOST}:8761" \
  -e POSTGRES_HOST="${INFRA_HOST}" \
  -e POSTGRES_USER=root \
  -e POSTGRES_PASSWORD="${COWORK_POSTGRES_PASSWORD}" \
  -e KAFKA_BOOTSTRAP_SERVERS="${INFRA_HOST}:9094" \
  -e REDIS_HOST="${INFRA_HOST}" \
  -e EUREKA_URL="http://${INFRA_HOST}:8761/eureka/" \
  -e EUREKA_INSTANCE_HOST="${SELF_IP}" \
  -e PREFERENCE_LOG_DIR=/var/log/cowork/preference \
  "${IMAGE}"

echo "[preference] waiting for health check"
for _ in $(seq 1 45); do
  if curl -sf "http://127.0.0.1:${PORT}/health/ready" >/dev/null; then
    echo "[preference] healthy"
    exit 0
  fi
  sleep 2
done

echo "[preference] FAILED health check after deploy" >&2
docker logs --tail 50 "${CONTAINER}" >&2 || true
exit 1
