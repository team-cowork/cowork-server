#!/bin/bash
# cowork-preference 배포 스크립트 (전용 VM, cowork-server_default 네트워크 밖 — 내부 IP 10.0.0.93으로 공유 인프라 접근)
#
# Vert.x/Kotlin 기반이라 Spring Cloud Config를 안 쓰고 자체 CONFIG_SERVER_URL 부트스트랩을
# 쓴다. PostgreSQL을 사용하는 유일한 서비스.
#
# 새 이미지를 임시 컨테이너로 먼저 헬스체크하고 통과했을 때만 기존 컨테이너를 교체한다
# (scripts/run/prod/_lib.sh의 deploy_container_safely) — 실패해도 서비스가 내려가지 않는다.
set -euo pipefail

: "${DEPLOY_IMAGE_OWNER:?DEPLOY_IMAGE_OWNER is required}"
: "${DEPLOY_IMAGE_TAG:?DEPLOY_IMAGE_TAG is required}"
: "${COWORK_POSTGRES_PASSWORD:?COWORK_POSTGRES_PASSWORD is required}"

# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

INFRA_HOST="10.0.0.93"
CONTAINER="cowork-preference"
IMAGE="ghcr.io/${DEPLOY_IMAGE_OWNER}/cowork-preference:${DEPLOY_IMAGE_TAG}"
PORT=9001
SELF_IP="$(advertise_ip "${INFRA_HOST}")"

ghcr_login_if_needed

echo "[preference] pulling ${IMAGE}"
docker pull "${IMAGE}"

deploy_container_safely "${CONTAINER}" "${PORT}" "${PORT}" "/health/ready" "${IMAGE}" -- \
  -e SPRING_PROFILES_ACTIVE=local \
  -e CONFIG_SERVER_URL="http://${INFRA_HOST}:8761" \
  -e POSTGRES_HOST="${INFRA_HOST}" \
  -e POSTGRES_USER=root \
  -e POSTGRES_PASSWORD="${COWORK_POSTGRES_PASSWORD}" \
  -e KAFKA_BOOTSTRAP_SERVERS="${INFRA_HOST}:9094" \
  -e REDIS_HOST="${INFRA_HOST}" \
  -e EUREKA_URL="http://${INFRA_HOST}:8761/eureka/" \
  -e EUREKA_INSTANCE_HOST="${SELF_IP}" \
  -e PREFERENCE_LOG_DIR=/var/log/cowork/preference
