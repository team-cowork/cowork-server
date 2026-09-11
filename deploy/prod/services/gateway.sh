#!/bin/bash
# Assignments are consumed by the sourcing deploy.sh and environment helpers.
# shellcheck disable=SC2034
# Runtime declaration, sourced by ../deploy.sh.
CONTAINER_PORT=8080
HOST_PORT=${HOST_PORT:-8080}
HEALTH_PATH=/actuator/health
spring_environment
add_env REDIS_HOST "$REDIS_HOST"
add_env REDIS_PORT "$REDIS_PORT"
