#!/usr/bin/env bash
# EC2 인스턴스에서 실행한다. GitHub Actions CD가 IMAGE_TAG를 넘겨 호출한다.
set -euo pipefail

cd "$(dirname "$0")/.."

: "${ECR_REGISTRY:?ECR_REGISTRY is required}"
: "${IMAGE_TAG:?IMAGE_TAG is required}"
export ECR_REGISTRY IMAGE_TAG

AWS_REGION="${AWS_REGION:-ap-northeast-2}"
COMPOSE="docker compose -f docker-compose.prod.yml"

aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$ECR_REGISTRY"

$COMPOSE pull app
$COMPOSE up -d --no-deps app

for attempt in $(seq 1 12); do
  status="$(docker inspect --format '{{.State.Health.Status}}' cowork-server 2>/dev/null || echo starting)"
  if [ "$status" = "healthy" ]; then
    echo "cowork-server is healthy (tag: $IMAGE_TAG)"
    $COMPOSE up -d nginx certbot
    docker image prune -af --filter "until=72h"
    exit 0
  fi
  echo "Waiting for cowork-server to become healthy ($attempt/12): $status"
  sleep 5
done

echo "cowork-server did not become healthy" >&2
docker logs --tail 200 cowork-server >&2
exit 1
