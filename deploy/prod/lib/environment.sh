#!/bin/bash
# Sourced by deploy.sh. These files are trusted operator-managed shell assignments.
load_deploy_environment() {
  local config_dir="${DEPLOY_CONFIG_DIR:-/etc/cowork}" file
  for file in "${config_dir}/common.env" "${config_dir}/${SERVICE}.env"; do
    if [ -f "$file" ]; then
      set -a
      # shellcheck source=/dev/null
      source "$file"
      set +a
    fi
  done
  set -a
  # shellcheck source=../runtime.env
  source "${PROD_DIR}/runtime.env"
  set +a
  APP_CONFIG_PROFILE=${APP_CONFIG_PROFILE:-prod}
  case "$APP_CONFIG_PROFILE" in local|prod) ;; *) fail 'APP_CONFIG_PROFILE must be local or prod' ;; esac
  HEALTH_TIMEOUT_SECONDS=${HEALTH_TIMEOUT_SECONDS:-420}
  [[ "$HEALTH_TIMEOUT_SECONDS" =~ ^[1-9][0-9]{0,3}$ ]] || fail 'HEALTH_TIMEOUT_SECONDS must be a positive integer of at most four digits'
}

fail() { echo "[deploy] $*" >&2; exit 1; }
require_env() {
  local name
  for name in "$@"; do
    [ -n "${!name:-}" ] || fail "${name} is required for ${SERVICE}"
  done
}

add_env() { RUN_ARGS+=(-e "$1=$2"); }

spring_environment() {
  add_env SPRING_PROFILES_ACTIVE "$APP_CONFIG_PROFILE"
  add_env SPRING_CONFIG_IMPORT "configserver:${CONFIG_SERVER_URL}"
  add_env KAFKA_BOOTSTRAP_SERVERS "$KAFKA_BOOTSTRAP_SERVERS"
  add_env EUREKA_CLIENT_SERVICEURL_DEFAULTZONE "$EUREKA_SERVER_URL"
  add_env EUREKA_INSTANCE_HOSTNAME "$ADVERTISE_IP"
  add_env EUREKA_INSTANCE_IP_ADDRESS "$ADVERTISE_IP"
  add_env EUREKA_INSTANCE_PREFER_IP_ADDRESS true
  add_env EUREKA_INSTANCE_NON_SECURE_PORT "$HOST_PORT"
}

custom_environment() {
  add_env APP_PROFILE "$APP_CONFIG_PROFILE"
  add_env APP_CONFIG_URL "$CONFIG_SERVER_URL"
  add_env EUREKA_SERVER_URL "$EUREKA_SERVER_URL"
  add_env EUREKA_INSTANCE_HOST "$ADVERTISE_IP"
  add_env EUREKA_INSTANCE_PORT "$HOST_PORT"
  add_env PORT "$CONTAINER_PORT"
}

mysql_environment() {
  require_env COWORK_MYSQL_PASSWORD
  add_env MYSQL_USER "$MYSQL_USER"
  add_env MYSQL_PASSWORD "$COWORK_MYSQL_PASSWORD"
  add_env SPRING_DATASOURCE_URL "jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/cowork_${SERVICE}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul"
}

object_storage_environment() {
  # Secret values may come from Vault; never replace them with development credentials.
  add_env S3_INTERNAL_ENDPOINT "$S3_INTERNAL_ENDPOINT"
  add_env S3_PUBLIC_ENDPOINT "$S3_PUBLIC_ENDPOINT"
  add_env S3_PUBLIC_BASE_URL "$S3_PUBLIC_BASE_URL"
  add_env S3_BUCKET "$S3_BUCKET"
  [ -z "${S3_ACCESS_KEY:-}" ] || add_env S3_ACCESS_KEY "$S3_ACCESS_KEY"
  [ -z "${S3_SECRET_KEY:-}" ] || add_env S3_SECRET_KEY "$S3_SECRET_KEY"
  return 0
}
