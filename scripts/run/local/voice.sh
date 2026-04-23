#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_service.sh"

SERVICE_NAME="cowork-voice"
SERVICE_WORKDIR="$PROJECT_ROOT/cowork-voice"
SERVICE_COMMAND=(
  bash -lc
  'export PORT="${VOICE_PORT:-8084}"
   export MONGODB_URI="${MONGODB_URI:-mongodb://${MONGO_ROOT_USERNAME}:${MONGO_ROOT_PASSWORD}@localhost:27017/cowork_voice?authSource=admin}"
   export MONGODB_DB="${MONGODB_DB:-cowork_voice}"
   export LIVEKIT_URL="${LIVEKIT_URL:-http://localhost:7880}"
   export LIVEKIT_WS_URL="${LIVEKIT_WS_URL:-ws://localhost:7880}"
   export KAFKA_BROKERS="${KAFKA_BROKERS:-${KAFKA_BOOTSTRAP_SERVERS:-localhost:9094}}"
   export KAFKA_TOPIC_VOICE_EVENT="${KAFKA_TOPIC_VOICE_EVENT:-voice.session.event}"
   export CHANNEL_SERVICE_URL="${CHANNEL_SERVICE_URL:-http://localhost:8080/api/channels}"
   go run ./cmd/server'
)

run_managed_service "${1:-start}"
