#!/bin/bash
# Assignments are consumed by the sourcing deploy.sh and environment helpers.
# shellcheck disable=SC2034
# Runtime declaration, sourced by ../deploy.sh.
CONTAINER_PORT=8081
HOST_PORT=${HOST_PORT:-8081}
HEALTH_PATH=/health
custom_environment
require_env COWORK_MYSQL_PASSWORD
add_env DB_DSN "${MYSQL_USER}:${COWORK_MYSQL_PASSWORD}@tcp(${MYSQL_HOST}:${MYSQL_PORT})/cowork_authorization?charset=utf8mb4&parseTime=True&loc=Local"
add_env KAFKA_BOOTSTRAP_SERVERS "$KAFKA_BOOTSTRAP_SERVERS"
add_env JWT_ACCESS_EXPIRE "${JWT_ACCESS_EXPIRE:-30m}"
add_env JWT_REFRESH_EXPIRE "${JWT_REFRESH_EXPIRE:-2160h}"
