#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

if [ ! -f "$PROJECT_ROOT/.env" ]; then
  echo "ERROR: .env file not found at $PROJECT_ROOT/.env"
  echo "Copy .env.example to .env and fill in the required values."
  exit 1
fi

set -a
source "$PROJECT_ROOT/.env"
set +a

REQUIRED_VARS=(
  MYSQL_ROOT_PASSWORD
  MYSQL_USER
  MYSQL_PASSWORD
  MONGO_ROOT_USERNAME
  MONGO_ROOT_PASSWORD
)

MISSING=()
for VAR in "${REQUIRED_VARS[@]}"; do
  if [ -z "${!VAR}" ]; then
    MISSING+=("$VAR")
  fi
done

if [ ${#MISSING[@]} -gt 0 ]; then
  echo "ERROR: The following required environment variables are not set:"
  for VAR in "${MISSING[@]}"; do
    echo "  - $VAR"
  done
  echo "Check your .env file at $PROJECT_ROOT/.env"
  exit 1
fi

cd "$PROJECT_ROOT"

echo ">>> Starting Docker Compose infrastructure..."
docker compose up -d

wait_healthy() {
  local container=$1
  local timeout_seconds=120
  local waited=0
  echo "Waiting for $container to be healthy..."
  until [ "$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null)" = "healthy" ]; do
    if [ "$waited" -ge "$timeout_seconds" ]; then
      echo "ERROR: Timed out waiting for $container to become healthy."
      exit 1
    fi
    sleep 2
    waited=$((waited + 2))
  done
  echo "$container is healthy."
}

CONTAINERS=(cowork-mysql cowork-mongodb cowork-kafka cowork-vault cowork-redis cowork-minio)

for c in "${CONTAINERS[@]}"; do
  wait_healthy "$c"
done

echo ">>> All infrastructure services are healthy."

shutdown() {
  echo ""
  echo ">>> Stopping infrastructure..."
  cd "$PROJECT_ROOT"
  docker compose down
  echo ">>> Infrastructure stopped."
  exit 0
}

trap shutdown SIGINT SIGTERM

echo ">>> Monitoring containers (Ctrl+C to stop all)..."
while true; do
  sleep 30 &
  wait $!
  UNHEALTHY=()
  for c in "${CONTAINERS[@]}"; do
    STATUS="$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null)"
    if [ "$STATUS" != "healthy" ]; then
      UNHEALTHY+=("$c($STATUS)")
    fi
  done
  if [ ${#UNHEALTHY[@]} -gt 0 ]; then
    echo "[$(date '+%H:%M:%S')] WARN: unhealthy containers: ${UNHEALTHY[*]}"
  else
    echo "[$(date '+%H:%M:%S')] OK: all containers healthy"
  fi
done
