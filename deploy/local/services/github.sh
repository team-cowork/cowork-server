#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/service.sh"

SERVICE_NAME="cowork-github"
SERVICE_WORKDIR="$PROJECT_ROOT/cowork-github"
SERVICE_COMMAND=(
  bash -lc
  'export GITHUB_APP_ID="${COWORK_GITHUB_APP_ID:-}"
   export GITHUB_PRIVATE_KEY="${COWORK_GITHUB_PRIVATE_KEY:-}"
   export GITHUB_WEBHOOK_SECRET="${COWORK_GITHUB_WEBHOOK_SECRET:-}"
   export INTERNAL_API_KEY="${COWORK_GITHUB_INTERNAL_API_KEY:-}"
   export APP_CONFIG_PROFILE="${APP_CONFIG_PROFILE:-local}"
   export PORT="${GITHUB_PORT:-8090}"
   export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9094}"
   export REDIS_HOST="${REDIS_HOST:-localhost}"
   npm run start:dev'
)

run_managed_service "${1:-start}"
