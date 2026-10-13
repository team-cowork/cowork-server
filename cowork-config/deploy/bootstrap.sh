#!/bin/sh
# Image-owned adapter for the versioned, runtime-neutral slot contract.
set -eu
: "${APP_CONFIG_PROFILE:?APP_CONFIG_PROFILE is required}"
: "${PORT:?PORT is required}"
export SPRING_PROFILES_ACTIVE="$APP_CONFIG_PROFILE"
export SERVER_PORT="$PORT"
export VAULT_HOST="${VAULT_EXTERNAL_HOST:?}"
export VAULT_SCHEME=https
exec "$@"
