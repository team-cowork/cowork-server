#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/service.sh"

SERVICE_NAME="cowork-preference"
SERVICE_WORKDIR="$PROJECT_ROOT"
SERVICE_COMMAND=(
  bash -lc
  'export DB_HOST="${DB_HOST:-localhost}"
   export DB_PORT="${DB_PORT:-5432}"
   export DB_NAME="${DB_NAME:-cowork_preference}"
   export DB_USERNAME="${DB_USERNAME:-${POSTGRES_USER}}"
   export DB_PASSWORD="${DB_PASSWORD:-${POSTGRES_PASSWORD}}"
   export REDIS_HOST="${REDIS_HOST:-localhost}"
   export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9094}"
   exec ./gradlew :cowork-preference:run'
)

run_managed_service "${1:-start}"
