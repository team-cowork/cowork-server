#!/bin/sh
# Image-owned adapter for the versioned, runtime-neutral slot contract.
set -eu
: "${APP_CONFIG_PROFILE:?APP_CONFIG_PROFILE is required}"
: "${PORT:?PORT is required}"
export APP_PROFILE="$APP_CONFIG_PROFILE"
export APP_CONFIG_URL="${CONFIG_SERVER_URL:?}"
export EUREKA_INSTANCE_HOST="${APP_ADVERTISE_ADDRESS:?}"
export EUREKA_INSTANCE_PORT="${APP_ADVERTISE_PORT:?}"
exec "$@"
