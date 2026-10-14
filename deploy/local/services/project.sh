#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/service.sh"

SERVICE_NAME="cowork-project"
SERVICE_WORKDIR="$PROJECT_ROOT"
SERVICE_COMMAND=(
  bash -lc
  'export DB_HOST="${DB_HOST:-localhost}"
   export DB_PORT="${DB_PORT:-3306}"
   export DB_NAME="${DB_NAME:-cowork_project}"
   export DB_USERNAME="${DB_USERNAME:-${MYSQL_USER}}"
   export DB_PASSWORD="${DB_PASSWORD:-${MYSQL_PASSWORD}}"
   export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9094}"
   export EUREKA_SERVER_URL="${EUREKA_SERVER_URL:-http://localhost:8761/eureka/}"
   export COWORK_GITHUB_APP_SERVICE_URL="${COWORK_GITHUB_APP_SERVICE_URL:-http://localhost:3000}"
   exec ./gradlew :cowork-project:run'
)

run_managed_service "${1:-start}"
