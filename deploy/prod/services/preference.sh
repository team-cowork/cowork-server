#!/bin/bash
# Assignments are consumed by the sourcing deploy.sh and environment helpers.
# shellcheck disable=SC2034
CONTAINER_PORT=9001
HOST_PORT=${HOST_PORT:-9001}
# The Vert.x client currently advertises server.port; keep its host port equal.
[ "$HOST_PORT" = "$CONTAINER_PORT" ] || fail 'preference requires HOST_PORT=9001'
HEALTH_PATH=/health/ready
require_env COWORK_POSTGRES_PASSWORD
add_env SPRING_PROFILES_ACTIVE "$APP_CONFIG_PROFILE"
add_env CONFIG_SERVER_URL "$CONFIG_SERVER_URL"
add_env POSTGRES_HOST "$POSTGRES_HOST"
add_env POSTGRES_PORT "$POSTGRES_PORT"
add_env POSTGRES_USER "$POSTGRES_USER"
add_env POSTGRES_PASSWORD "$COWORK_POSTGRES_PASSWORD"
add_env KAFKA_BOOTSTRAP_SERVERS "$KAFKA_BOOTSTRAP_SERVERS"
add_env REDIS_HOST "$REDIS_HOST"
add_env REDIS_PORT "$REDIS_PORT"
add_env EUREKA_SERVER_URL "$EUREKA_SERVER_URL"
add_env EUREKA_INSTANCE_HOST "$ADVERTISE_IP"
add_env PREFERENCE_LOG_DIR /var/log/cowork/preference
