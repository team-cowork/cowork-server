#!/bin/bash
# Assignments are consumed by the sourcing deploy.sh and environment helpers.
# shellcheck disable=SC2034
CONTAINER_PORT=8082
HOST_PORT=${HOST_PORT:-8082}
HEALTH_PATH=/actuator/health/readiness
custom_environment
require_env COWORK_MYSQL_PASSWORD
# The image entrypoint reads remaining secrets from Config Server and runs Flyway.
add_env DB_HOST "$MYSQL_HOST"
add_env DB_PORT "$MYSQL_PORT"
add_env DB_NAME cowork_user
add_env DB_USERNAME "$MYSQL_USER"
add_env DB_PASSWORD "$COWORK_MYSQL_PASSWORD"
add_env DB_JDBC_URL "jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/cowork_user?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul"
add_env KAFKA_BOOTSTRAP_SERVERS "$KAFKA_BOOTSTRAP_SERVERS"
add_env REDIS_HOST "$REDIS_HOST"
add_env REDIS_PORT "$REDIS_PORT"
object_storage_environment
