#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/service.sh"

SERVICE_NAME="cowork-gateway"
SERVICE_WORKDIR="$PROJECT_ROOT"
SERVICE_COMMAND=(
  bash -lc
  'export COWORK_AUTHORIZATION_URL="${COWORK_AUTHORIZATION_URL:-http://localhost:8081}"
   export COWORK_USER_URL="${COWORK_USER_URL:-http://localhost:8082}"
   export COWORK_CHANNEL_URL="${COWORK_CHANNEL_URL:-http://localhost:8083}"
   export COWORK_PROJECT_URL="${COWORK_PROJECT_URL:-http://localhost:8084}"
   export COWORK_TEAM_URL="${COWORK_TEAM_URL:-http://localhost:8085}"
   export COWORK_NOTIFICATION_URL="${COWORK_NOTIFICATION_URL:-http://localhost:8086}"
   export COWORK_CHAT_URL="${COWORK_CHAT_URL:-http://localhost:8087}"
   export COWORK_ROADMAP_URL="${COWORK_ROADMAP_URL:-http://localhost:8088}"
   export COWORK_VOICE_URL="${COWORK_VOICE_URL:-http://localhost:8089}"
   export COWORK_GITHUB_URL="${COWORK_GITHUB_URL:-http://localhost:8090}"
   export COWORK_PREFERENCE_URL="${COWORK_PREFERENCE_URL:-http://localhost:9001}"
   export COWORK_CHAT_WS_URL="${COWORK_CHAT_WS_URL:-ws://localhost:8087}"
   export S3_TEAM_INTERNAL_ENDPOINT="${S3_TEAM_INTERNAL_ENDPOINT:-http://localhost:9000}"
   export S3_USER_INTERNAL_ENDPOINT="${S3_USER_INTERNAL_ENDPOINT:-http://localhost:9000}"
   export S3_CHAT_INTERNAL_ENDPOINT="${S3_CHAT_INTERNAL_ENDPOINT:-http://localhost:9000}"
   export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9094}"
   export REDIS_HOST="${REDIS_HOST:-localhost}"
   exec ./gradlew :cowork-gateway:bootRun'
)

run_managed_service "${1:-start}"
