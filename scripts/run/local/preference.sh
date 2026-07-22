#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_service.sh"

SERVICE_NAME="cowork-preference"
SERVICE_WORKDIR="$PROJECT_ROOT"
SERVICE_COMMAND=(
  bash -lc
  'export CONFIG_SERVER_URL="${CONFIG_SERVER_URL:-http://localhost:8761}"
   export POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
   export REDIS_HOST="${REDIS_HOST:-localhost}"
   export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9094}"
   export EUREKA_URL="${EUREKA_URL:-http://localhost:8761/eureka/}"
   export EUREKA_INSTANCE_HOST="${EUREKA_INSTANCE_HOST:-localhost}"
   exec ./gradlew :cowork-preference:run'
)

run_managed_service "${1:-start}"
