#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/service.sh"

SERVICE_NAME="cowork-github"
SERVICE_WORKDIR="$PROJECT_ROOT/cowork-github"
SERVICE_COMMAND=(
  bash -lc
  'export CONFIG_SERVER_URL="${CONFIG_SERVER_URL:-http://localhost:8761}"
   export APP_CONFIG_PROFILE="${APP_CONFIG_PROFILE:-local}"
   export PORT="${GITHUB_PORT:-8090}"
   export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9094}"
   export REDIS_HOST="${REDIS_HOST:-localhost}"
   export EUREKA_SERVER_URL="${EUREKA_SERVER_URL:-http://localhost:8761/eureka}"
   export APP_ADVERTISE_ADDRESS="${APP_ADVERTISE_ADDRESS:-localhost}"
   npm run start:dev'
)

run_managed_service "${1:-start}"
