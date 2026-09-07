#!/bin/bash
# cowork-gateway 배포 스크립트 (VM: cowork-db / 21108, cowork-server_default 네트워크)
#
# 서버에서 이미지를 다시 빌드하지 않고, CD가 이미 빌드해 ghcr.io에 올린 이미지를 그대로 pull해서 띄운다.
# DEPLOY_SHA/DEPLOY_IMAGE_OWNER/DEPLOY_IMAGE_TAG는 CD 워크플로가 SSH 세션에 주입한다.
#
# 멱등적으로 동작한다: 네트워크가 없으면 만들고, 기존 컨테이너가 있든 없든 안전하게 정리한 뒤
# 새로 띄우고, 헬스체크로 기동 성공을 스스로 검증한다. 실패하면 non-zero exit으로 끝난다.
#
# TODO(prod-compose-cutover): SPRING_PROFILES_ACTIVE=local이 docker-compose.prod.yml의 prod와
# 다르다. cowork-config가 prod 프로필로 전환 가능해지면(config.sh의 TODO 참고) 이 스크립트도 지우고
#   REGISTRY="ghcr.io/${DEPLOY_IMAGE_OWNER}" IMAGE_TAG="${DEPLOY_IMAGE_TAG}" \
#     docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d cowork-gateway
# 한 줄로 대체한다.
set -euo pipefail

: "${DEPLOY_IMAGE_OWNER:?DEPLOY_IMAGE_OWNER is required}"
: "${DEPLOY_IMAGE_TAG:?DEPLOY_IMAGE_TAG is required}"

NETWORK="cowork-server_default"
CONTAINER="cowork-gateway"
IMAGE="ghcr.io/${DEPLOY_IMAGE_OWNER}/cowork-gateway:${DEPLOY_IMAGE_TAG}"
PORT=8080

echo "[gateway] pulling ${IMAGE}"
docker pull "${IMAGE}"

docker network inspect "${NETWORK}" >/dev/null 2>&1 || docker network create "${NETWORK}"

echo "[gateway] removing existing container (if any)"
docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true

echo "[gateway] starting new container"
docker run -d --name "${CONTAINER}" --restart unless-stopped \
  -p ${PORT}:${PORT} \
  -e SPRING_PROFILES_ACTIVE=local \
  -e SPRING_CONFIG_IMPORT=optional:configserver:http://cowork-config:8761 \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  -e REDIS_HOST=redis \
  -e EUREKA_CLIENT_SERVICEURL_DEFAULTZONE=http://cowork-config:8761/eureka/ \
  --network "${NETWORK}" \
  "${IMAGE}"

echo "[gateway] waiting for health check"
for _ in $(seq 1 30); do
  if curl -sf "http://127.0.0.1:${PORT}/actuator/health" >/dev/null; then
    echo "[gateway] healthy"
    exit 0
  fi
  sleep 2
done

echo "[gateway] FAILED health check after deploy" >&2
docker logs --tail 50 "${CONTAINER}" >&2 || true
exit 1
