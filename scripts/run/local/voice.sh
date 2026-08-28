#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_service.sh"

SERVICE_NAME="cowork-voice"
SERVICE_WORKDIR="$PROJECT_ROOT/cowork-voice"
SERVICE_COMMAND=(
  bash -lc
  'export APP_CONFIG_URL="${APP_CONFIG_URL:-http://localhost:8761}"
   export APP_PROFILE="${APP_PROFILE:-local}"
   export PORT="${VOICE_PORT:-8089}"
   export MONGODB_URI="${MONGODB_URI:-mongodb://${MONGO_ROOT_USERNAME}:${MONGO_ROOT_PASSWORD}@localhost:27017/cowork_voice?authSource=admin}"
   export MONGODB_DB="${MONGODB_DB:-cowork_voice}"
   export REDIS_ADDR="${REDIS_ADDR:-localhost:6379}"
   export LIVEKIT_URL="${LIVEKIT_URL:-http://localhost:7880}"
   export LIVEKIT_WS_URL="${LIVEKIT_WS_URL:-ws://localhost:7880}"
   export KAFKA_BROKERS="${KAFKA_BROKERS:-${KAFKA_BOOTSTRAP_SERVERS:-localhost:9094}}"
   export KAFKA_TOPIC_VOICE_EVENT="${KAFKA_TOPIC_VOICE_EVENT:-voice.event}"
   export KAFKA_TOPIC_CHANNEL_MEMBER_EVENT="${KAFKA_TOPIC_CHANNEL_MEMBER_EVENT:-channel.member.event}"
   export KAFKA_GROUP_ID_CHANNEL_MEMBER="${KAFKA_GROUP_ID_CHANNEL_MEMBER:-cowork-voice.channel-member}"
   export EUREKA_SERVER_URL="${EUREKA_SERVER_URL:-http://localhost:8761/eureka}"
   export EUREKA_INSTANCE_HOST="${EUREKA_INSTANCE_HOST:-localhost}"
   go run ./cmd/server'
)

run_managed_service "${1:-start}"
