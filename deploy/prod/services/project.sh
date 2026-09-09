#!/bin/bash
# Assignments are consumed by the sourcing deploy.sh and environment helpers.
# shellcheck disable=SC2034
# Runtime declaration, sourced by ../deploy.sh.
CONTAINER_PORT=8084
HOST_PORT=${HOST_PORT:-8089}
HEALTH_PATH=/actuator/health/readiness
spring_environment
mysql_environment
require_env COWORK_GITHUB_APP_INTERNAL_API_KEY
add_env GITHUB_APP_SERVICE_URL "$GITHUB_APP_SERVICE_URL"
add_env GITHUB_APP_INTERNAL_API_KEY "$COWORK_GITHUB_APP_INTERNAL_API_KEY"
