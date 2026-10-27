#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/service.sh"

SERVICE_NAME="cowork-channel"
SERVICE_WORKDIR="$PROJECT_ROOT"
SERVICE_COMMAND=(
  bash -lc
  'export GITHUB_ACCOUNT_SHARE_CLIENT_ID="${COWORK_GITHUB_ACCOUNT_SHARE_CLIENT_ID:-}"
   export GITHUB_ACCOUNT_SHARE_CLIENT_SECRET="${COWORK_GITHUB_ACCOUNT_SHARE_CLIENT_SECRET:-}"
   export DB_HOST="${DB_HOST:-localhost}"
   export DB_PORT="${DB_PORT:-3306}"
   export DB_NAME="${DB_NAME:-cowork_channel}"
   export DB_USERNAME="${DB_USERNAME:-${MYSQL_USER}}"
   export DB_PASSWORD="${DB_PASSWORD:-${MYSQL_PASSWORD}}"
   export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9094}"
   exec ./gradlew :cowork-channel:bootRun'
)

run_managed_service "${1:-start}"
