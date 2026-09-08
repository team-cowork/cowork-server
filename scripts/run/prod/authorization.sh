#!/bin/bash
# cowork-authorization 배포 스크립트 (전용 VM, cowork-server_default 네트워크 밖 — 내부 IP 10.0.0.93으로 공유 인프라 접근)
#
# Go 서비스라 config-server 부트스트랩이 Spring 계열과 다르다: APP_CONFIG_URL/APP_PROFILE로
# 설정을 가져오고, Eureka 등록도 자체 구현한 클라이언트가 EUREKA_SERVER_URL/EUREKA_INSTANCE_HOST를 쓴다.
set -euo pipefail

: "${DEPLOY_IMAGE_OWNER:?DEPLOY_IMAGE_OWNER is required}"
: "${DEPLOY_IMAGE_TAG:?DEPLOY_IMAGE_TAG is required}"
: "${COWORK_MYSQL_PASSWORD:?COWORK_MYSQL_PASSWORD is required}"

INFRA_HOST="10.0.0.93"
CONTAINER="cowork-authorization"
IMAGE="ghcr.io/${DEPLOY_IMAGE_OWNER}/cowork-authorization:${DEPLOY_IMAGE_TAG}"
PORT=8081
SELF_IP="$(hostname -I | awk '{print $1}')"

echo "[authorization] pulling ${IMAGE}"
docker pull "${IMAGE}"

echo "[authorization] removing existing container (if any)"
docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true

echo "[authorization] starting new container"
docker run -d --name "${CONTAINER}" --restart unless-stopped \
  -p ${PORT}:${PORT} \
  -e APP_CONFIG_URL="http://${INFRA_HOST}:8761" \
  -e APP_PROFILE=local \
  -e DB_DSN="cowork:${COWORK_MYSQL_PASSWORD}@tcp(${INFRA_HOST}:3306)/cowork_authorization?charset=utf8mb4&parseTime=True&loc=Local" \
  -e KAFKA_BOOTSTRAP_SERVERS="${INFRA_HOST}:9094" \
  -e EUREKA_SERVER_URL="http://${INFRA_HOST}:8761/eureka" \
  -e EUREKA_INSTANCE_HOST="${SELF_IP}" \
  -e DATAGSM_USERINFO_URL="https://oauth.resource.datagsm.kr/userinfo" \
  -e DATAGSM_TOKEN_URL="https://oauth.authorization.datagsm.kr/v1/oauth/token" \
  -e USER_SERVICE_URL="http://10.0.0.144:8082" \
  -e JWT_ACCESS_EXPIRE=30m \
  -e JWT_REFRESH_EXPIRE=2160h \
  "${IMAGE}"

echo "[authorization] waiting for health check"
for _ in $(seq 1 45); do
  if curl -sf "http://127.0.0.1:${PORT}/health" >/dev/null; then
    echo "[authorization] healthy"
    exit 0
  fi
  sleep 2
done

echo "[authorization] FAILED health check after deploy" >&2
docker logs --tail 50 "${CONTAINER}" >&2 || true
exit 1
