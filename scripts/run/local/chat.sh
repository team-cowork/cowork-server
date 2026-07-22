#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_service.sh"

SERVICE_NAME="cowork-chat"
SERVICE_WORKDIR="$PROJECT_ROOT/cowork-chat"
SERVICE_COMMAND=(
  bash -lc
  'export APP_CONFIG_URL="${APP_CONFIG_URL:-http://localhost:8761}"
   export APP_PROFILE="${APP_PROFILE:-local}"
   export PORT="${CHAT_PORT:-8087}"
   export ALLOWED_ORIGINS="${ALLOWED_ORIGINS:-http://localhost:3000}"
   export MONGODB_URI="${MONGODB_URI:-mongodb://${MONGO_ROOT_USERNAME}:${MONGO_ROOT_PASSWORD}@localhost:27017/cowork_chat?authSource=admin}"
   export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9094}"
   export PROJECT_SERVICE_URL="${PROJECT_SERVICE_URL:-http://localhost:8084}"
   export CHANNEL_SERVICE_URL="${CHANNEL_SERVICE_URL:-http://localhost:8083}"
   export USER_SERVICE_URL="${USER_SERVICE_URL:-http://localhost:8082}"
   export REDIS_HOST="${REDIS_HOST:-localhost}"
   export ELASTICSEARCH_URL="http://localhost:9200"
   export EUREKA_SERVER_URL="${EUREKA_SERVER_URL:-http://localhost:8761/eureka}"
   export EUREKA_INSTANCE_HOST="${EUREKA_INSTANCE_HOST:-localhost}"
   npm run start:dev'
)

run_managed_service "${1:-start}"
