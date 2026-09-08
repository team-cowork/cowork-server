#!/bin/bash
# cowork-team 배포 스크립트 (VM: cowork-db / 21108, cowork-server_default 네트워크 — gateway/config와 동일 VM)
#
# CD가 ghcr.io에 올린 이미지를 pull해서 띄운다. 같은 VM 안의 mysql/kafka/cowork-config는
# 컨테이너 이름으로 접근하지만, team 자신을 Eureka에 등록할 때는 다른 VM의 서비스들도
# 찾아올 수 있도록 이 VM의 사설 IP(10.0.0.93)로 광고한다.
set -euo pipefail

: "${DEPLOY_IMAGE_OWNER:?DEPLOY_IMAGE_OWNER is required}"
: "${DEPLOY_IMAGE_TAG:?DEPLOY_IMAGE_TAG is required}"
: "${COWORK_MYSQL_PASSWORD:?COWORK_MYSQL_PASSWORD is required}"

NETWORK="cowork-server_default"
CONTAINER="cowork-team"
IMAGE="ghcr.io/${DEPLOY_IMAGE_OWNER}/cowork-team:${DEPLOY_IMAGE_TAG}"
PORT=8085
SELF_IP="$(hostname -I | awk '{print $1}')"

echo "[team] pulling ${IMAGE}"
docker pull "${IMAGE}"

docker network inspect "${NETWORK}" >/dev/null 2>&1 || docker network create "${NETWORK}"

echo "[team] removing existing container (if any)"
docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true

echo "[team] starting new container"
docker run -d --name "${CONTAINER}" --restart unless-stopped \
  -p ${PORT}:${PORT} \
  -e SPRING_PROFILES_ACTIVE=local \
  -e SPRING_CONFIG_IMPORT=optional:configserver:http://cowork-config:8761 \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://mysql:3306/cowork_team?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul" \
  -e MYSQL_USER=cowork \
  -e MYSQL_PASSWORD="${COWORK_MYSQL_PASSWORD}" \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  -e EUREKA_CLIENT_SERVICEURL_DEFAULTZONE=http://cowork-config:8761/eureka/ \
  -e EUREKA_INSTANCE_HOSTNAME="${SELF_IP}" \
  -e EUREKA_INSTANCE_IP_ADDRESS="${SELF_IP}" \
  -e EUREKA_INSTANCE_PREFER_IP_ADDRESS=true \
  -e S3_INTERNAL_ENDPOINT=http://cowork-minio:9000 \
  -e S3_PUBLIC_ENDPOINT=http://ssh.gsmsv.site:22108 \
  -e S3_ACCESS_KEY=minioadmin \
  -e S3_SECRET_KEY=minioadmin \
  -e S3_BUCKET=cowork-bucket \
  -e S3_REGION=ap-northeast-2 \
  --network "${NETWORK}" \
  "${IMAGE}"

echo "[team] waiting for health check"
for _ in $(seq 1 45); do
  if curl -sf "http://127.0.0.1:${PORT}/actuator/health/readiness" >/dev/null; then
    echo "[team] healthy"
    exit 0
  fi
  sleep 2
done

echo "[team] FAILED health check after deploy" >&2
docker logs --tail 50 "${CONTAINER}" >&2 || true
exit 1
