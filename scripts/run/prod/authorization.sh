#!/bin/bash
# cowork-authorization 배포 스크립트 (전용 VM, cowork-server_default 네트워크 밖 — 내부 IP 10.0.0.93으로 공유 인프라 접근)
#
# Go 서비스라 config-server 부트스트랩이 Spring 계열과 다르다: APP_CONFIG_URL/APP_PROFILE로
# 설정을 가져오고, Eureka 등록도 자체 구현한 클라이언트가 EUREKA_SERVER_URL/EUREKA_INSTANCE_HOST를 쓴다.
#
# 새 이미지를 임시 컨테이너로 먼저 헬스체크하고 통과했을 때만 기존 컨테이너를 교체한다
# (scripts/run/prod/_lib.sh의 deploy_container_safely) — 실패해도 서비스가 내려가지 않는다.
set -euo pipefail

: "${DEPLOY_IMAGE_OWNER:?DEPLOY_IMAGE_OWNER is required}"
: "${DEPLOY_IMAGE_TAG:?DEPLOY_IMAGE_TAG is required}"
: "${COWORK_MYSQL_PASSWORD:?COWORK_MYSQL_PASSWORD is required}"

# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

INFRA_HOST="10.0.0.93"
CONTAINER="cowork-authorization"
IMAGE="ghcr.io/${DEPLOY_IMAGE_OWNER}/cowork-authorization:${DEPLOY_IMAGE_TAG}"
PORT=8081
SELF_IP="$(advertise_ip "${INFRA_HOST}")"

ghcr_login_if_needed

echo "[authorization] pulling ${IMAGE}"
docker pull "${IMAGE}"

deploy_container_safely "${CONTAINER}" "${PORT}" "${PORT}" "/health" "${IMAGE}" -- \
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
  -e JWT_REFRESH_EXPIRE=2160h
