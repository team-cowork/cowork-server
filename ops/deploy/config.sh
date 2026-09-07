#!/bin/bash
# cowork-config 배포 스크립트 (VM: cowork-db / 21108, cowork-server_default 네트워크)
#
# CD가 ghcr.io에 올린 이미지를 pull해서 기존 config server를 교체한다.
# VAULT_TOKEN은 dev 모드 고정 root token(dev-root-token, docker-compose.yml에도 이미 평문으로 있음)이라
# 실제 비밀값이 아니며, 별도 시크릿 주입이 필요 없다.
set -euo pipefail

: "${DEPLOY_IMAGE_OWNER:?DEPLOY_IMAGE_OWNER is required}"
: "${DEPLOY_IMAGE_TAG:?DEPLOY_IMAGE_TAG is required}"

NETWORK="cowork-server_default"
CONTAINER="cowork-config"
IMAGE="ghcr.io/${DEPLOY_IMAGE_OWNER}/cowork-config:${DEPLOY_IMAGE_TAG}"
PORT=8761

echo "[config] pulling ${IMAGE}"
docker pull "${IMAGE}"

docker network inspect "${NETWORK}" >/dev/null 2>&1 || docker network create "${NETWORK}"

echo "[config] removing existing container (if any)"
docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true

echo "[config] starting new container"
docker run -d --name "${CONTAINER}" --restart unless-stopped \
  -p ${PORT}:${PORT} \
  -e SPRING_PROFILES_ACTIVE=local \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  -e VAULT_HOST=cowork-vault \
  --network "${NETWORK}" \
  "${IMAGE}"

echo "[config] waiting for health check"
for _ in $(seq 1 45); do
  if curl -sf "http://127.0.0.1:${PORT}/actuator/health" >/dev/null; then
    echo "[config] healthy"
    exit 0
  fi
  sleep 2
done

echo "[config] FAILED health check after deploy" >&2
docker logs --tail 50 "${CONTAINER}" >&2 || true
exit 1
