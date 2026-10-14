#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/service.sh"

SERVICE_NAME="cowork-authorization"
SERVICE_WORKDIR="$PROJECT_ROOT/cowork-authorization"
SERVICE_COMMAND=(
  bash -lc
  'export DB_HOST="${DB_HOST:-localhost}"
   export DB_PORT="${DB_PORT:-3306}"
   export DB_NAME="${DB_NAME:-cowork_authorization}"
   export DB_USERNAME="${DB_USERNAME:-${MYSQL_USER}}"
   export DB_PASSWORD="${DB_PASSWORD:-${MYSQL_PASSWORD}}"
   export CONFIG_SERVER_URL="${CONFIG_SERVER_URL:-http://localhost:8761}"
   export APP_CONFIG_PROFILE="${APP_CONFIG_PROFILE:-local}"
   export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9094}"
   export EUREKA_SERVER_URL="${EUREKA_SERVER_URL:-http://localhost:8761/eureka}"
   export APP_ADVERTISE_ADDRESS="${APP_ADVERTISE_ADDRESS:-localhost}"
   go run ./cmd'
)

run_managed_service "${1:-start}"
