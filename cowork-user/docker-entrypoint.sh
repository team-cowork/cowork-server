#!/bin/sh
set -eu

if [ -n "${DB_JDBC_URL:-}" ]; then
  export FLYWAY_URL="${DB_JDBC_URL}"
fi

if [ -n "${DB_USERNAME:-}" ]; then
  export FLYWAY_USER="${DB_USERNAME}"
fi

if [ -n "${DB_PASSWORD:-}" ]; then
  export FLYWAY_PASSWORD="${DB_PASSWORD}"
fi

if [ -n "${FLYWAY_URL:-}" ]; then
  /flyway/flyway migrate
fi

mkdir -p "$(dirname "${LOG_PATH:-/var/log/cowork/user/application.log}")"
exec /app/bin/cowork_user start
