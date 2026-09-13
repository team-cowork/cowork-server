#!/bin/bash
# Assignments are consumed by the sourcing deploy.sh and environment helpers.
# shellcheck disable=SC2034
# Runtime declaration, sourced by ../deploy.sh.
CONTAINER_PORT=8088
HOST_PORT=${HOST_PORT:-8088}
HEALTH_PATH=/actuator/health/readiness
spring_environment
mysql_environment
add_env SPRING_R2DBC_URL "r2dbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/cowork_roadmap?serverZoneId=Asia/Seoul"
