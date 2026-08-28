#!/bin/sh
set -eu

config_value() {
  key=$1
  printf '%s' "${CONFIG_SERVER_RESPONSE}" | jq -r --arg key "${key}" '
    [.propertySources[]?.source[$key] | select(. != null and . != "")][0]
    | if . == null then "" elif type == "string" then . else tostring end
  '
}

set_from_config() {
  env_name=$1
  shift

  if [ -n "$(printenv "${env_name}" 2>/dev/null || true)" ]; then
    return
  fi

  for key in "$@"; do
    value=$(config_value "${key}")
    if [ -n "${value}" ]; then
      export "${env_name}=${value}"
      return
    fi
  done
}

if [ -n "${APP_CONFIG_URL:-}" ]; then
  config_profile=${APP_PROFILE:-local}
  config_url="${APP_CONFIG_URL%/}/cowork-user/${config_profile}"
  CONFIG_SERVER_RESPONSE=$(curl --fail --silent --show-error --max-time 10 "${config_url}")

  set_from_config PORT PORT SERVER_PORT server_port
  set_from_config DB_HOST DB_HOST db_host
  set_from_config DB_PORT DB_PORT db_port
  set_from_config DB_NAME DB_NAME db_name
  set_from_config DB_JDBC_URL DB_JDBC_URL db_jdbc_url
  set_from_config DB_USERNAME DB_USERNAME db_username
  set_from_config DB_PASSWORD DB_PASSWORD db_password
  set_from_config DB_POOL_SIZE DB_POOL_SIZE db_pool_size
fi

: "${DB_HOST:?DB_HOST is required}"
: "${DB_PORT:?DB_PORT is required}"
: "${DB_NAME:?DB_NAME is required}"
: "${DB_JDBC_URL:?DB_JDBC_URL is required}"
: "${DB_USERNAME:?DB_USERNAME is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"

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
