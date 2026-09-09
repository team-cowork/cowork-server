#!/bin/bash
# cowork-config 배포 스크립트 (VM: cowork-db / 21108, cowork-server_default 네트워크)
#
# CD가 ghcr.io에 올린 이미지를 pull해서 기존 config server를 교체한다.
#
# 새 이미지를 임시 컨테이너로 먼저 헬스체크하고 통과했을 때만 기존 컨테이너를 교체한다
# (scripts/run/prod/_lib.sh의 deploy_container_safely) — 실패해도 서비스가 내려가지 않는다.
#
# TODO(prod-compose-cutover): 이 스크립트는 docker-compose.prod.yml과 아래 두 지점이 의도적으로
# 다르다 — 저장소의 선언(docker-compose.prod.yml)을 그대로 안 쓰고 여기서 다시 적은 건 중복이라
# 바람직하지 않지만, 지금 서버가 아직 그 계약을 못 채워서 그대로 쓰면 배포가 즉시 실패한다:
#   - SPRING_PROFILES_ACTIVE=local (prod.yml은 prod) — PUBLIC_WEB_ORIGIN/PUBLIC_API_BASE_URL/
#     GITHUB_APP_SERVICE_URL 실값이 채워지기 전까지는 local 유지
#   - -p ${PORT}:${PORT} 로 호스트 포트 노출 (prod.yml은 ports: !reset [] 로 비공개) — 다른
#     서비스들이 아직 게이트웨이를 거치지 않고 이 포트로 직접 붙는 경우가 있어 당장 못 막음.
#     (리뷰에서 127.0.0.1로 좁히자는 제안이 있었지만, 그 "직접 붙는 서비스들"이 전부 다른
#     VM에 있어서 127.0.0.1 바인딩은 그쪽 호출까지 막아버린다 — 전부 마이그레이션 끝난
#     뒤에나 안전하게 좁힐 수 있다.)
# VAULT_HOST/VAULT_SCHEME=https는 이미 docker/vault/docker-compose.vault.yml 기반의
# 영구 저장+TLS Vault(scripts/run/prod/vault.sh)를 가리키도록 아래에서 실제로 맞춰져 있다 —
# 남은 TODO는 위 두 가지뿐이다.
set -euo pipefail

: "${DEPLOY_IMAGE_OWNER:?DEPLOY_IMAGE_OWNER is required}"
: "${DEPLOY_IMAGE_TAG:?DEPLOY_IMAGE_TAG is required}"
: "${VAULT_EXTERNAL_HOST:?VAULT_EXTERNAL_HOST is required}"
: "${VAULT_TOKEN:?VAULT_TOKEN is required}"

# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

NETWORK="cowork-server_default"
CONTAINER="cowork-config"
IMAGE="ghcr.io/${DEPLOY_IMAGE_OWNER}/cowork-config:${DEPLOY_IMAGE_TAG}"
PORT=8761

ghcr_login_if_needed

echo "[config] pulling ${IMAGE}"
docker pull "${IMAGE}"

docker network inspect "${NETWORK}" >/dev/null 2>&1 || docker network create "${NETWORK}"

deploy_container_safely "${CONTAINER}" "${PORT}" "${PORT}" "/actuator/health" "${IMAGE}" -- \
  -e SPRING_PROFILES_ACTIVE=local \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  -e VAULT_HOST="${VAULT_EXTERNAL_HOST}" \
  -e VAULT_SCHEME=https \
  -e VAULT_PORT=443 \
  -e VAULT_TOKEN="${VAULT_TOKEN}" \
  --network "${NETWORK}"
