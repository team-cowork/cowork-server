#!/bin/sh
# Image-owned adapter for the versioned, runtime-neutral slot contract.
set -eu
: "${APP_CONFIG_PROFILE:?APP_CONFIG_PROFILE is required}"
: "${PORT:?PORT is required}"
export SPRING_PROFILES_ACTIVE="$APP_CONFIG_PROFILE"
export POSTGRES_HOST="${DB_HOST:?}"
export POSTGRES_PORT="${DB_PORT:?}"
export POSTGRES_DATABASE="${DB_NAME:?}"
export POSTGRES_USER="${DB_USERNAME:?}"
export POSTGRES_PASSWORD="${DB_PASSWORD:?}"
export EUREKA_INSTANCE_HOST="${APP_ADVERTISE_ADDRESS:?}"
export EUREKA_INSTANCE_PORT="${APP_ADVERTISE_PORT:?}"
exec "$@"
