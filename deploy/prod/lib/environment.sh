#!/bin/bash
# Only validated release slots and Vault snapshots enter this environment.
load_deploy_environment() {
  [ -n "${DEPLOY_SETTINGS_DIR:-}" ] || fail 'Use the deployment workflow to load a release slot and Vault secrets'
  set -a
  # shellcheck source=../defaults.sh
  source "${PROD_DIR}/defaults.sh"
  set +a
  case "$SERVICE" in
    vault|monitoring|datastores|storage|log-agent|node-agent) ;;
    *) require_env APP_CONFIG_PROFILE SLOT_KIND
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

control_plane_environment() {
  require_env CONFIG_SERVER_URL EUREKA_SERVER_URL CONFIG_CLIENT_USERNAME CONFIG_CLIENT_PASSWORD
  if [ "$APP_CONFIG_PROFILE" = prod ]; then
    python3 "${PROD_DIR}/config-access.py"
  fi
}
