#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/service.sh"

SERVICE_NAME="cowork-voice"
SERVICE_WORKDIR="$PROJECT_ROOT/cowork-voice"
SERVICE_COMMAND=(
  bash -lc
  'export DB_HOST="${DB_HOST:-localhost}"
   export DB_PORT="${DB_PORT:-27017}"
   export DB_NAME="${DB_NAME:-cowork_voice}"
   export DB_USERNAME="${DB_USERNAME:-${MONGO_ROOT_USERNAME}}"
   export DB_PASSWORD="${DB_PASSWORD:-${MONGO_ROOT_PASSWORD}}"
   export APP_CONFIG_PROFILE="${APP_CONFIG_PROFILE:-local}"
   export PORT="${VOICE_PORT:-8089}"
   export LIVEKIT_URL="${LIVEKIT_URL:-http://localhost:7880}"
   export LIVEKIT_WS_URL="${LIVEKIT_WS_URL:-ws://localhost:7880}"
   export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9094}"
   export KAFKA_TOPIC_VOICE_EVENT="${KAFKA_TOPIC_VOICE_EVENT:-voice.event}"
   export KAFKA_GROUP_ID_CHANNEL_ACCESS="${KAFKA_GROUP_ID_CHANNEL_ACCESS:-cowork-voice.channel-permissions.v1}"
   go run ./cmd/server'
)

run_managed_service "${1:-start}"
