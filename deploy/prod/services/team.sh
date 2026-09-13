#!/bin/bash
# Assignments are consumed by the sourcing deploy.sh and environment helpers.
# shellcheck disable=SC2034
# Runtime declaration, sourced by ../deploy.sh.
CONTAINER_PORT=8085
HOST_PORT=${HOST_PORT:-8085}
HEALTH_PATH=/actuator/health/readiness
spring_environment
mysql_environment
object_storage_environment
