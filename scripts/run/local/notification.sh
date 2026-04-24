#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_service.sh"

SERVICE_NAME="cowork-notification"
SERVICE_WORKDIR="$PROJECT_ROOT/cowork-notification"
SERVICE_COMMAND=(
  bash -lc
  'export APP_CONFIG_URL="http://localhost:8761"
   export APP_PROFILE="local"
   export DB_DSN="${DB_DSN:-${MYSQL_USER}:${MYSQL_PASSWORD}@tcp(localhost:3306)/cowork_notification?charset=utf8mb4&parseTime=True&loc=Local}"
   export TEAM_SERVICE_URL="${TEAM_SERVICE_URL:-http://localhost:8085}"
   export USER_SERVICE_URL="${USER_SERVICE_URL:-http://localhost:8082}"
   export PREFERENCE_SERVICE_URL="${PREFERENCE_SERVICE_URL:-http://localhost:9001}"
   export KAFKA_BROKERS="${KAFKA_BROKERS:-${KAFKA_BOOTSTRAP_SERVERS:-localhost:9094}}"
   export KAFKA_TOPIC_NOTIFICATION="${KAFKA_TOPIC_NOTIFICATION:-notification.trigger}"
   export KAFKA_GROUP_ID="${KAFKA_GROUP_ID:-cowork-notification}"
   exec go run ./cmd/server/'
)

run_managed_service "${1:-start}"
