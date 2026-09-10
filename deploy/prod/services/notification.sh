#!/bin/bash
# Assignments are consumed by the sourcing deploy.sh and environment helpers.
# shellcheck disable=SC2034
# Runtime declaration, sourced by ../deploy.sh.
CONTAINER_PORT=8086
HOST_PORT=${HOST_PORT:-8086}
HEALTH_PATH=/health/ready
custom_environment
require_env COWORK_MYSQL_PASSWORD
add_env DB_DSN "${MYSQL_USER}:${COWORK_MYSQL_PASSWORD}@tcp(${MYSQL_HOST}:${MYSQL_PORT})/cowork_notification?charset=utf8mb4&parseTime=True&loc=Local"
add_env KAFKA_BROKERS "$KAFKA_BOOTSTRAP_SERVERS"
