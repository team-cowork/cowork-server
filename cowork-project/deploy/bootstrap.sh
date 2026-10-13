#!/bin/sh
# Image-owned adapter for the versioned, runtime-neutral slot contract.
set -eu
: "${APP_CONFIG_PROFILE:?APP_CONFIG_PROFILE is required}"
: "${PORT:?PORT is required}"
export SPRING_PROFILES_ACTIVE="$APP_CONFIG_PROFILE"
export SERVER_PORT="$PORT"
export EUREKA_CLIENT_SERVICEURL_DEFAULTZONE="${EUREKA_SERVER_URL:?}"
export EUREKA_INSTANCE_HOSTNAME="${APP_ADVERTISE_ADDRESS:?}"
export EUREKA_INSTANCE_IP_ADDRESS="$APP_ADVERTISE_ADDRESS"
export EUREKA_INSTANCE_PREFER_IP_ADDRESS=true
export EUREKA_INSTANCE_NON_SECURE_PORT="${APP_ADVERTISE_PORT:?}"
export MYSQL_USER="${DB_USERNAME:?}"
export MYSQL_PASSWORD="${DB_PASSWORD:?}"
export SPRING_DATASOURCE_URL="jdbc:mysql://${DB_HOST:?}:${DB_PORT:?}/${DB_NAME:?}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul"
exec "$@"
