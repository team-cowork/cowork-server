#!/bin/sh
# Image-owned adapter for the versioned, runtime-neutral slot contract.
set -eu
: "${APP_CONFIG_PROFILE:?APP_CONFIG_PROFILE is required}"
: "${PORT:?PORT is required}"
export APP_PROFILE="$APP_CONFIG_PROFILE"
export APP_CONFIG_URL="${CONFIG_SERVER_URL:?}"
export EUREKA_INSTANCE_HOST="${APP_ADVERTISE_ADDRESS:?}"
export EUREKA_INSTANCE_PORT="${APP_ADVERTISE_PORT:?}"
# URI-encode credentials without exposing them to a process argument or log.
uri_encode() { od -An -v -tx1 | awk '{for (i = 1; i <= NF; i++) printf "%%%s", toupper($i)}'; }
encoded_user=$(printf '%s' "${DB_USERNAME:?}" | uri_encode)
encoded_password=$(printf '%s' "${DB_PASSWORD:?}" | uri_encode)
export MONGODB_URI="mongodb://${encoded_user}:${encoded_password}@${DB_HOST:?}:${DB_PORT:?}/${DB_NAME:?}?authSource=admin"
exec "$@"
