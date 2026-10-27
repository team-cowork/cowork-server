#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/service.sh"

SERVICE_NAME="cowork-chat"
SERVICE_WORKDIR="$PROJECT_ROOT/cowork-chat"
SERVICE_COMMAND=(
  bash -lc
  'export S3_ACCESS_KEY="${S3_CHAT_ACCESS_KEY:-local-chat}"
   export S3_SECRET_KEY="${S3_CHAT_SECRET_KEY:-local-chat-secret}"
   export DB_HOST="${DB_HOST:-localhost}"
   export DB_PORT="${DB_PORT:-27017}"
   export DB_NAME="${DB_NAME:-cowork_chat}"
   export DB_USERNAME="${DB_USERNAME:-${MONGO_ROOT_USERNAME}}"
   export DB_PASSWORD="${DB_PASSWORD:-${MONGO_ROOT_PASSWORD}}"
   export APP_CONFIG_PROFILE="${APP_CONFIG_PROFILE:-local}"
   export PORT="${CHAT_PORT:-8087}"
   export ALLOWED_ORIGINS="${ALLOWED_ORIGINS:-http://localhost:3000}"
   export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9094}"
   export REDIS_HOST="${REDIS_HOST:-localhost}"
   export S3_INTERNAL_ENDPOINT="${S3_INTERNAL_ENDPOINT:-http://localhost:9000}"
   export ELASTICSEARCH_URL="${ELASTICSEARCH_URL:-http://localhost:9200}"
   npm run start:dev'
)

run_managed_service "${1:-start}"
