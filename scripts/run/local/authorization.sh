#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_service.sh"

SERVICE_NAME="cowork-authorization"
SERVICE_WORKDIR="$PROJECT_ROOT/cowork-authorization"
SERVICE_COMMAND=(
  bash -lc
  'export APP_CONFIG_URL="${APP_CONFIG_URL:-http://localhost:8761}"
   export APP_PROFILE="${APP_PROFILE:-local}"
   export DB_DSN="${DB_DSN:-${MYSQL_USER}:${MYSQL_PASSWORD}@tcp(localhost:3306)/cowork_authorization?charset=utf8mb4&parseTime=True&loc=Local}"
   export USER_SERVICE_URL="${USER_SERVICE_URL:-http://localhost:8082}"
   go run ./cmd'
)

run_managed_service "${1:-start}"
