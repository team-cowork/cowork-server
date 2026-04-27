#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

REQUIRED_VARS=(
  MYSQL_ROOT_PASSWORD
  MYSQL_USER
  MYSQL_PASSWORD
  MONGO_ROOT_USERNAME
  MONGO_ROOT_PASSWORD
)

CONTAINERS=(cowork-mysql cowork-mongodb cowork-kafka cowork-vault cowork-redis cowork-minio)

load_env() {
  if [ ! -f "$PROJECT_ROOT/.env" ]; then
    echo "ERROR: .env file not found at $PROJECT_ROOT/.env"
    echo "Copy .env.example to .env and fill in the required values."
    exit 1
  fi

  set -a
  source "$PROJECT_ROOT/.env"
  set +a
}

validate_env() {
  local missing=()

  for var in "${REQUIRED_VARS[@]}"; do
    if [ -z "${!var:-}" ]; then
      missing+=("$var")
    fi
  done

  if [ ${#missing[@]} -gt 0 ]; then
    echo "ERROR: The following required environment variables are not set:"
    for var in "${missing[@]}"; do
      echo "  - $var"
    done
    echo "Check your .env file at $PROJECT_ROOT/.env"
    exit 1
  fi
}

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

start_infra() {
  load_env
  validate_env
  cd "$PROJECT_ROOT"

  echo ">>> Starting Docker Compose infrastructure..."
  docker compose up -d

  for container in "${CONTAINERS[@]}"; do
    wait_healthy "$container"
  done

  echo ">>> All infrastructure services are healthy."
}

stop_infra() {
  cd "$PROJECT_ROOT"
  docker compose down
}

status_infra() {
  cd "$PROJECT_ROOT"
  docker compose ps
}

logs_infra() {
  cd "$PROJECT_ROOT"
  docker compose logs -f
}

monitor_infra() {
  start_infra

  shutdown() {
    echo ""
    echo ">>> Stopping infrastructure..."
    stop_infra
    echo ">>> Infrastructure stopped."
    exit 0
  }

  trap shutdown SIGINT SIGTERM

  echo ">>> Monitoring containers (Ctrl+C to stop all)..."
  while true; do
    sleep 30 &
    wait $!
    unhealthy=()
    for container in "${CONTAINERS[@]}"; do
      status="$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null)"
      if [ "$status" != "healthy" ]; then
        unhealthy+=("$container($status)")
      fi
    done
    if [ ${#unhealthy[@]} -gt 0 ]; then
      echo "[$(date '+%H:%M:%S')] WARN: unhealthy containers: ${unhealthy[*]}"
    else
      echo "[$(date '+%H:%M:%S')] OK: all containers healthy"
    fi
  done
}

case "${1:-start}" in
  start)
    start_infra
    ;;
  stop)
    stop_infra
    ;;
  restart)
    stop_infra
    start_infra
    ;;
  status)
    status_infra
    ;;
  logs)
    logs_infra
    ;;
  monitor)
    monitor_infra
    ;;
  *)
    echo "Usage: $0 [start|stop|restart|status|logs|monitor]"
    exit 1
    ;;
esac
