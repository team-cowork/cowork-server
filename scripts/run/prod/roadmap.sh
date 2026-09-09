#!/bin/bash
# cowork-roadmap 배포 스크립트 (전용 VM, cowork-server_default 네트워크 밖 — 내부 IP 10.0.0.93으로 공유 인프라 접근)
#
# CD가 ghcr.io에 올린 이미지를 pull해서 띄운다. MySQL 비밀번호는 저장소에 두지 않고
# GitHub Secrets(COWORK_MYSQL_PASSWORD)에서 배포 시점에 env로 주입받는다.
#
# 새 이미지를 임시 컨테이너로 먼저 헬스체크하고 통과했을 때만 기존 컨테이너를 교체한다
# (scripts/run/prod/_lib.sh의 deploy_container_safely) — 실패해도 서비스가 내려가지 않는다.
#
# NOTE: gateway.sh/config.sh와 달리 이 스크립트는 docker-compose.prod.yml 기반 한 줄 배포로
# 대체될 대상이 아니다 — roadmap은 cowork-server_default 컴포즈 프로젝트와 물리적으로 분리된
# 별도 VM에 있어서, 같은 compose 프로젝트로 `up -d`하면 그 VM에 로컬 mysql/config 등을
# 새로 띄우려 들게 된다. 공유 인프라를 내부 IP(10.0.0.93)로 바라보는 지금 구조가 맞다.
set -euo pipefail

: "${DEPLOY_IMAGE_OWNER:?DEPLOY_IMAGE_OWNER is required}"
: "${DEPLOY_IMAGE_TAG:?DEPLOY_IMAGE_TAG is required}"
: "${COWORK_MYSQL_PASSWORD:?COWORK_MYSQL_PASSWORD is required}"

# shellcheck source=/dev/null
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

INFRA_HOST="10.0.0.93"
CONTAINER="cowork-roadmap"
IMAGE="ghcr.io/${DEPLOY_IMAGE_OWNER}/cowork-roadmap:${DEPLOY_IMAGE_TAG}"
PORT=8088
SELF_IP="$(advertise_ip "${INFRA_HOST}")"

ghcr_login_if_needed

echo "[roadmap] pulling ${IMAGE}"
docker pull "${IMAGE}"

deploy_container_safely "${CONTAINER}" "${PORT}" "${PORT}" "/actuator/health" "${IMAGE}" -- \
  -e SPRING_PROFILES_ACTIVE=local \
  -e SPRING_CONFIG_IMPORT="configserver:http://${INFRA_HOST}:8761" \
  -e SPRING_R2DBC_URL="r2dbc:mysql://${INFRA_HOST}:3306/cowork_roadmap?serverZoneId=Asia/Seoul" \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://${INFRA_HOST}:3306/cowork_roadmap?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul" \
  -e MYSQL_USER=cowork \
  -e MYSQL_PASSWORD="${COWORK_MYSQL_PASSWORD}" \
  -e KAFKA_BOOTSTRAP_SERVERS="${INFRA_HOST}:9094" \
  -e EUREKA_CLIENT_SERVICEURL_DEFAULTZONE="http://${INFRA_HOST}:8761/eureka/" \
  -e EUREKA_INSTANCE_HOSTNAME="${SELF_IP}" \
  -e EUREKA_INSTANCE_PREFER_IP_ADDRESS=true
