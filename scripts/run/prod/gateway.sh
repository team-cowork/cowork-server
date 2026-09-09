#!/bin/bash
# cowork-gateway 배포 스크립트 (VM: cowork-db / 21108, cowork-server_default 네트워크)
#
# 서버에서 이미지를 다시 빌드하지 않고, CD가 이미 빌드해 ghcr.io에 올린 이미지를 그대로 pull해서 띄운다.
# DEPLOY_SHA/DEPLOY_IMAGE_OWNER/DEPLOY_IMAGE_TAG는 CD 워크플로가 SSH 세션에 주입한다.
#
# 새 이미지를 임시 컨테이너로 먼저 헬스체크하고 통과했을 때만 기존 컨테이너를 교체한다
# (scripts/run/prod/_lib.sh의 deploy_container_safely) — 실패해도 서비스가 내려가지 않는다.
#
# TODO(prod-compose-cutover): SPRING_PROFILES_ACTIVE=local이 docker-compose.prod.yml의 prod와
# 다르다. cowork-config가 prod 프로필로 전환 가능해지면(config.sh의 TODO 참고) 이 스크립트도 지우고
#   REGISTRY="ghcr.io/${DEPLOY_IMAGE_OWNER}" IMAGE_TAG="${DEPLOY_IMAGE_TAG}" \
#     docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d cowork-gateway
# 한 줄로 대체한다.
set -euo pipefail

: "${DEPLOY_IMAGE_OWNER:?DEPLOY_IMAGE_OWNER is required}"
: "${DEPLOY_IMAGE_TAG:?DEPLOY_IMAGE_TAG is required}"

# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

NETWORK="cowork-server_default"
CONTAINER="cowork-gateway"
IMAGE="ghcr.io/${DEPLOY_IMAGE_OWNER}/cowork-gateway:${DEPLOY_IMAGE_TAG}"
PORT=8080

ghcr_login_if_needed

echo "[gateway] pulling ${IMAGE}"
docker pull "${IMAGE}"

docker network inspect "${NETWORK}" >/dev/null 2>&1 || docker network create "${NETWORK}"

deploy_container_safely "${CONTAINER}" "${PORT}" "${PORT}" "/actuator/health" "${IMAGE}" -- \
  -e SPRING_PROFILES_ACTIVE=local \
  -e SPRING_CONFIG_IMPORT=configserver:http://cowork-config:8761 \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  -e REDIS_HOST=redis \
  -e EUREKA_CLIENT_SERVICEURL_DEFAULTZONE=http://cowork-config:8761/eureka/ \
  --network "${NETWORK}"
