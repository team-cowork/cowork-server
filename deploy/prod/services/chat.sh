#!/bin/bash
# Assignments are consumed by the sourcing deploy.sh and environment helpers.
# shellcheck disable=SC2034
# Runtime declaration, sourced by ../deploy.sh.
CONTAINER_PORT=8087
HOST_PORT=${HOST_PORT:-8087}
HEALTH_PATH=/health/ready
custom_environment
require_env COWORK_MONGO_PASSWORD
add_env MONGODB_URI "mongodb://${MONGO_USER}:${COWORK_MONGO_PASSWORD}@${MONGO_HOST}:${MONGO_PORT}/cowork_chat?authSource=admin"
require_env JWT_SECRET
add_env JWT_SECRET "$JWT_SECRET"
add_env KAFKA_BOOTSTRAP_SERVERS "$KAFKA_BOOTSTRAP_SERVERS"
add_env REDIS_HOST "$REDIS_HOST"
add_env REDIS_PORT "$REDIS_PORT"
add_env ELASTICSEARCH_URL "$ELASTICSEARCH_URL"
object_storage_environment
