#!/bin/bash
# cowork-project 배포 스크립트 (전용 VM, cowork-server_default 네트워크 밖 — 내부 IP 10.0.0.93으로 공유 인프라 접근)
#
# 이 VM은 컨테이너 내부 포트(8084)와 호스트 공개 포트(8089)가 다르다 — 다른 서비스들이
# 이미 이 VM을 8089로 알고 있어서(예: cowork-chat의 PROJECT_SERVICE_URL) 그대로 유지한다.
# Eureka에도 실제로 외부에서 도달 가능한 8089를 광고해야 한다.
#
# 새 이미지를 임시 컨테이너로 먼저 헬스체크하고 통과했을 때만 기존 컨테이너를 교체한다
# (scripts/run/prod/_lib.sh의 deploy_container_safely) — 실패해도 서비스가 내려가지 않는다.
set -euo pipefail

: "${DEPLOY_IMAGE_OWNER:?DEPLOY_IMAGE_OWNER is required}"
: "${DEPLOY_IMAGE_TAG:?DEPLOY_IMAGE_TAG is required}"
: "${COWORK_MYSQL_PASSWORD:?COWORK_MYSQL_PASSWORD is required}"
: "${COWORK_GITHUB_APP_INTERNAL_API_KEY:?COWORK_GITHUB_APP_INTERNAL_API_KEY is required}"

# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

INFRA_HOST="10.0.0.93"
CONTAINER="cowork-project"
IMAGE="ghcr.io/${DEPLOY_IMAGE_OWNER}/cowork-project:${DEPLOY_IMAGE_TAG}"
INTERNAL_PORT=8084
EXTERNAL_PORT=8089
SELF_IP="$(advertise_ip "${INFRA_HOST}")"

ghcr_login_if_needed

echo "[project] pulling ${IMAGE}"
docker pull "${IMAGE}"

deploy_container_safely "${CONTAINER}" "${EXTERNAL_PORT}" "${INTERNAL_PORT}" "/actuator/health/readiness" "${IMAGE}" -- \
  -e SPRING_PROFILES_ACTIVE=local \
  -e SPRING_CONFIG_IMPORT="configserver:http://${INFRA_HOST}:8761" \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://${INFRA_HOST}:3306/cowork_project?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul" \
  -e MYSQL_USER=cowork \
  -e MYSQL_PASSWORD="${COWORK_MYSQL_PASSWORD}" \
  -e KAFKA_BOOTSTRAP_SERVERS="${INFRA_HOST}:9094" \
  -e EUREKA_CLIENT_SERVICEURL_DEFAULTZONE="http://${INFRA_HOST}:8761/eureka/" \
  -e EUREKA_INSTANCE_HOSTNAME="${SELF_IP}" \
  -e EUREKA_INSTANCE_IP_ADDRESS="${SELF_IP}" \
  -e EUREKA_INSTANCE_PREFER_IP_ADDRESS=true \
  -e EUREKA_INSTANCE_NON_SECURE_PORT="${EXTERNAL_PORT}" \
  -e GITHUB_APP_SERVICE_URL="http://10.0.0.150:3000/" \
  -e GITHUB_APP_INTERNAL_API_KEY="${COWORK_GITHUB_APP_INTERNAL_API_KEY}"
