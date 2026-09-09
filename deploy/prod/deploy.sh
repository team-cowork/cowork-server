#!/bin/bash
set -euo pipefail

PROD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELEASE_ROOT="$(cd "${PROD_DIR}/../.." && pwd)"
export RELEASE_ROOT
SERVICE="${1:-}"
ACTION="${2:-deploy}"
case "$SERVICE" in
  config|gateway|authorization|user|team|channel|project|roadmap|notification|preference|chat|voice|monitoring|vault|log-agent) ;;
  *) echo "Usage: $0 <service> [check|deploy]" >&2; exit 1 ;;
esac
case "$ACTION" in check|deploy) ;; *) echo 'Action must be check or deploy' >&2; exit 1 ;; esac

# shellcheck source=lib/environment.sh
source "${PROD_DIR}/lib/environment.sh"
# shellcheck source=lib/container.sh
source "${PROD_DIR}/lib/container.sh"
load_deploy_environment

STATE_DIR="${DEPLOY_STATE_DIR:-${HOME}/.local/state/cowork}"
if [ "$ACTION" = deploy ]; then
  mkdir -p "$STATE_DIR"
  exec 8>"${STATE_DIR}/${SERVICE}.lock"
  flock -w 120 8 || fail "Another ${SERVICE} deployment is running"
fi

case "$SERVICE" in
  vault|monitoring|log-agent)
    # These deployment units have their own lifecycle; none starts the application stack.
    # shellcheck source=/dev/null
    source "${PROD_DIR}/services/${SERVICE}.sh"
    exit
    ;;
esac

require_env DEPLOY_IMAGE_OWNER DEPLOY_IMAGE_TAG
CONTAINER="cowork-${SERVICE}"
IMAGE="${DEPLOY_REGISTRY:-ghcr.io/${DEPLOY_IMAGE_OWNER}}/${CONTAINER}:${DEPLOY_IMAGE_TAG}"
require_env ADVERTISE_IP
# By default only the gateway listens on all host interfaces.
if [ "$SERVICE" = gateway ]; then
  BIND_IP="${BIND_IP:-0.0.0.0}"
else
  BIND_IP="${BIND_IP:-${ADVERTISE_IP}}"
fi
HEALTH_PATH=
RUN_ARGS=(--log-driver json-file --log-opt max-size=20m --log-opt max-file=5)
# shellcheck source=/dev/null
source "${PROD_DIR}/services/${SERVICE}.sh"
require_env HOST_PORT CONTAINER_PORT HEALTH_PATH

# Explicit container-only overrides support application values without shell evaluation.
while IFS= read -r -d '' entry; do
  RUN_ARGS+=(-e "$entry")
done < "${DEPLOY_SETTINGS_DIR}/application.env0"

if [ "$ACTION" = check ]; then
  printf '[deploy] %s: image=%s profile=%s bind=%s:%s container-port=%s advertise=%s:%s health=%s timeout=%ss\n' \
    "$SERVICE" "$IMAGE" "$APP_CONFIG_PROFILE" "$BIND_IP" "$HOST_PORT" "$CONTAINER_PORT" "$ADVERTISE_IP" "$HOST_PORT" "$HEALTH_PATH" "$HEALTH_TIMEOUT_SECONDS"
  exit
fi

ghcr_login_if_needed
docker pull "$IMAGE"
deploy_container_safely "$CONTAINER" "$HOST_PORT" "$CONTAINER_PORT" "$HEALTH_PATH" "$IMAGE" -- "${RUN_ARGS[@]}"
