#!/bin/bash
# cowork-roadmap 배포 스크립트 (전용 VM, cowork-server_default 네트워크 밖 — 내부 IP 10.0.0.93으로 공유 인프라 접근)
#
# CD가 ghcr.io에 올린 이미지를 pull해서 띄운다. MySQL 비밀번호는 저장소에 두지 않고
# GitHub Secrets(COWORK_MYSQL_PASSWORD)에서 배포 시점에 env로 주입받는다.
#
# NOTE: gateway.sh/config.sh와 달리 이 스크립트는 docker-compose.prod.yml 기반 한 줄 배포로
# 대체될 대상이 아니다 — roadmap은 cowork-server_default 컴포즈 프로젝트와 물리적으로 분리된
# 별도 VM에 있어서, 같은 compose 프로젝트로 `up -d`하면 그 VM에 로컬 mysql/config 등을
# 새로 띄우려 들게 된다. 공유 인프라를 내부 IP(10.0.0.93)로 바라보는 지금 구조가 맞다.
set -euo pipefail

: "${DEPLOY_IMAGE_OWNER:?DEPLOY_IMAGE_OWNER is required}"
: "${DEPLOY_IMAGE_TAG:?DEPLOY_IMAGE_TAG is required}"
: "${COWORK_MYSQL_PASSWORD:?COWORK_MYSQL_PASSWORD is required}"

INFRA_HOST="10.0.0.93"
CONTAINER="cowork-roadmap"
IMAGE="ghcr.io/${DEPLOY_IMAGE_OWNER}/cowork-roadmap:${DEPLOY_IMAGE_TAG}"
PORT=8088

echo "[roadmap] pulling ${IMAGE}"
docker pull "${IMAGE}"

echo "[roadmap] removing existing container (if any)"
docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true

echo "[roadmap] starting new container"
docker run -d --name "${CONTAINER}" --restart unless-stopped \
  -p ${PORT}:${PORT} \
  -e SPRING_PROFILES_ACTIVE=local \
  -e SPRING_CONFIG_IMPORT="optional:configserver:http://${INFRA_HOST}:8761" \
  -e SPRING_R2DBC_URL="r2dbc:mysql://${INFRA_HOST}:3306/cowork_roadmap?serverZoneId=Asia/Seoul" \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://${INFRA_HOST}:3306/cowork_roadmap?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul" \
  -e MYSQL_USER=cowork \
  -e MYSQL_PASSWORD="${COWORK_MYSQL_PASSWORD}" \
  -e KAFKA_BOOTSTRAP_SERVERS="${INFRA_HOST}:9094" \
  -e EUREKA_CLIENT_SERVICEURL_DEFAULTZONE="http://${INFRA_HOST}:8761/eureka/" \
  -e EUREKA_INSTANCE_HOSTNAME="$(hostname -I | awk '{print $1}')" \
  -e EUREKA_INSTANCE_PREFER_IP_ADDRESS=true \
  "${IMAGE}"

echo "[roadmap] waiting for health check"
for _ in $(seq 1 45); do
  if curl -sf "http://127.0.0.1:${PORT}/actuator/health" >/dev/null; then
    echo "[roadmap] healthy"
    exit 0
  fi
  sleep 2
done

echo "[roadmap] FAILED health check after deploy" >&2
docker logs --tail 50 "${CONTAINER}" >&2 || true
exit 1
