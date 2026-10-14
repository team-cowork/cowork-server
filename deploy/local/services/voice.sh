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
   export CONFIG_SERVER_URL="${CONFIG_SERVER_URL:-http://localhost:8761}"
   export APP_CONFIG_PROFILE="${APP_CONFIG_PROFILE:-local}"
   export PORT="${VOICE_PORT:-8089}"
   export LIVEKIT_URL="${LIVEKIT_URL:-http://localhost:7880}"
   export LIVEKIT_WS_URL="${LIVEKIT_WS_URL:-ws://localhost:7880}"
   export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9094}"
   export KAFKA_TOPIC_VOICE_EVENT="${KAFKA_TOPIC_VOICE_EVENT:-voice.event}"
   export KAFKA_TOPIC_CHANNEL_MEMBER_EVENT="${KAFKA_TOPIC_CHANNEL_MEMBER_EVENT:-channel.member.event.v2}"
   export KAFKA_GROUP_ID_CHANNEL_MEMBER="${KAFKA_GROUP_ID_CHANNEL_MEMBER:-cowork-voice.channel-member.v2}"
   export EUREKA_SERVER_URL="${EUREKA_SERVER_URL:-http://localhost:8761/eureka}"
   export APP_ADVERTISE_ADDRESS="${APP_ADVERTISE_ADDRESS:-localhost}"
   go run ./cmd/server'
)

run_managed_service "${1:-start}"
