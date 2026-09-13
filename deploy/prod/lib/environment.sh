#!/bin/bash
# Sourced by deploy.sh after the validated Vault snapshot has been loaded.
load_deploy_environment() {
  [ -n "${DEPLOY_SETTINGS_DIR:-}" ] || fail 'Use the GitHub deployment workflow to load settings from Vault'
  set -a
  # shellcheck source=../defaults.sh
  source "${PROD_DIR}/defaults.sh"
  set +a
  case "$SERVICE" in
    vault|monitoring|log-agent) ;;
    *) require_env APP_CONFIG_PROFILE
       case "$APP_CONFIG_PROFILE" in local|prod) ;; *) fail 'APP_CONFIG_PROFILE must be local or prod' ;; esac ;;
  esac
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
  require_env CONFIG_SERVER_URL EUREKA_SERVER_URL KAFKA_BOOTSTRAP_SERVERS
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
  require_env CONFIG_SERVER_URL EUREKA_SERVER_URL
  add_env APP_PROFILE "$APP_CONFIG_PROFILE"
  add_env APP_CONFIG_URL "$CONFIG_SERVER_URL"
  add_env EUREKA_SERVER_URL "$EUREKA_SERVER_URL"
  add_env EUREKA_INSTANCE_HOST "$ADVERTISE_IP"
  add_env EUREKA_INSTANCE_PORT "$HOST_PORT"
  add_env PORT "$CONTAINER_PORT"
}

mysql_environment() {
  require_env MYSQL_HOST MYSQL_USER COWORK_MYSQL_PASSWORD
  add_env MYSQL_USER "$MYSQL_USER"
  add_env MYSQL_PASSWORD "$COWORK_MYSQL_PASSWORD"
  add_env SPRING_DATASOURCE_URL "jdbc:mysql://${MYSQL_HOST:?MYSQL_HOST is required}:${MYSQL_PORT}/cowork_${SERVICE}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul"
}

object_storage_environment() {
  require_env S3_INTERNAL_ENDPOINT S3_PUBLIC_ENDPOINT S3_PUBLIC_BASE_URL
  # Secret values may come from Vault; never replace them with development credentials.
  add_env S3_INTERNAL_ENDPOINT "$S3_INTERNAL_ENDPOINT"
  add_env S3_PUBLIC_ENDPOINT "$S3_PUBLIC_ENDPOINT"
  add_env S3_PUBLIC_BASE_URL "$S3_PUBLIC_BASE_URL"
  add_env S3_BUCKET "$S3_BUCKET"
  [ -z "${S3_ACCESS_KEY:-}" ] || add_env S3_ACCESS_KEY "$S3_ACCESS_KEY"
  [ -z "${S3_SECRET_KEY:-}" ] || add_env S3_SECRET_KEY "$S3_SECRET_KEY"
  return 0
}
