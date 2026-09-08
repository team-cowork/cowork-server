#!/bin/bash
# cowork-channel 배포 스크립트 (전용 VM, cowork-server_default 네트워크 밖 — 내부 IP 10.0.0.93으로 공유 인프라 접근)
set -euo pipefail

: "${DEPLOY_IMAGE_OWNER:?DEPLOY_IMAGE_OWNER is required}"
: "${DEPLOY_IMAGE_TAG:?DEPLOY_IMAGE_TAG is required}"
: "${COWORK_MYSQL_PASSWORD:?COWORK_MYSQL_PASSWORD is required}"

INFRA_HOST="10.0.0.93"
CONTAINER="cowork-channel"
IMAGE="ghcr.io/${DEPLOY_IMAGE_OWNER}/cowork-channel:${DEPLOY_IMAGE_TAG}"
PORT=8083
SELF_IP="$(hostname -I | awk '{print $1}')"

echo "[channel] pulling ${IMAGE}"
docker pull "${IMAGE}"

echo "[channel] removing existing container (if any)"
docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true

echo "[channel] starting new container"
docker run -d --name "${CONTAINER}" --restart unless-stopped \
  -p ${PORT}:${PORT} \
  -e SPRING_PROFILES_ACTIVE=local \
  -e SPRING_CONFIG_IMPORT="optional:configserver:http://${INFRA_HOST}:8761" \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://${INFRA_HOST}:3306/cowork_channel?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul" \
  -e MYSQL_USER=cowork \
  -e MYSQL_PASSWORD="${COWORK_MYSQL_PASSWORD}" \
  -e KAFKA_BOOTSTRAP_SERVERS="${INFRA_HOST}:9094" \
  -e EUREKA_CLIENT_SERVICEURL_DEFAULTZONE="http://${INFRA_HOST}:8761/eureka/" \
  -e EUREKA_INSTANCE_HOSTNAME="${SELF_IP}" \
  -e EUREKA_INSTANCE_IP_ADDRESS="${SELF_IP}" \
  -e EUREKA_INSTANCE_PREFER_IP_ADDRESS=true \
  "${IMAGE}"

echo "[channel] waiting for health check"
for _ in $(seq 1 45); do
  if curl -sf "http://127.0.0.1:${PORT}/actuator/health/readiness" >/dev/null; then
    echo "[channel] healthy"
    exit 0
  fi
  sleep 2
done

echo "[channel] FAILED health check after deploy" >&2
docker logs --tail 50 "${CONTAINER}" >&2 || true
exit 1
