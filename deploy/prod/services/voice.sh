#!/bin/bash
# Assignments are consumed by the sourcing deploy.sh and environment helpers.
# shellcheck disable=SC2034
# Runtime declaration, sourced by ../deploy.sh.
CONTAINER_PORT=8089
HOST_PORT=${HOST_PORT:-8089}
HEALTH_PATH=/health/ready
custom_environment
add_env KAFKA_BROKERS "$KAFKA_BOOTSTRAP_SERVERS"
require_env COWORK_MONGO_PASSWORD
add_env MONGODB_URI "mongodb://${MONGO_USER}:${COWORK_MONGO_PASSWORD}@${MONGO_HOST}:${MONGO_PORT}/cowork_voice?authSource=admin"
require_env LIVEKIT_API_KEY LIVEKIT_API_SECRET
add_env MONGODB_DB cowork_voice
add_env REDIS_ADDR "${REDIS_HOST}:${REDIS_PORT}"
add_env LIVEKIT_URL "$LIVEKIT_URL"
add_env LIVEKIT_WS_URL "$LIVEKIT_WS_URL"
add_env LIVEKIT_API_KEY "$LIVEKIT_API_KEY"
add_env LIVEKIT_API_SECRET "$LIVEKIT_API_SECRET"
