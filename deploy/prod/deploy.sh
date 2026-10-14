#!/bin/bash
set -euo pipefail

PROD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELEASE_ROOT="$(cd "${PROD_DIR}/../.." && pwd)"
export RELEASE_ROOT
SERVICE="${1:-}"
ACTION="${2:-deploy}"
case "$SERVICE" in
  config|gateway|authorization|user|team|channel|project|roadmap|notification|preference|chat|voice|monitoring|vault|datastores|storage|log-agent) ;;
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
  vault|monitoring|datastores|storage|log-agent)
    # These deployment units have their own lifecycle; none starts the application stack.
    # shellcheck source=/dev/null
    source "${PROD_DIR}/services/${SERVICE}.sh"
    exit
    ;;
esac

require_env DEPLOY_IMAGE_OWNER DEPLOY_IMAGE_TAG
CONTAINER="cowork-${SERVICE}"
IMAGE="${DEPLOY_REGISTRY:-ghcr.io/${DEPLOY_IMAGE_OWNER}}/${CONTAINER}:${DEPLOY_IMAGE_TAG}"
require_env ADVERTISE_IP BIND_IP HOST_PORT CONTAINER_PORT HEALTH_PATH SLOT_KIND STOP_TIMEOUT_SECONDS
RUN_ARGS=(--log-driver json-file --log-opt max-size=20m --log-opt max-file=5 --log-opt labels=cowork.service --label "cowork.service=${SERVICE}")
while IFS= read -r -d '' argument; do
  RUN_ARGS+=("$argument")
done < "${DEPLOY_SETTINGS_DIR}/container.args0"
if [ "$SLOT_KIND" = config ]; then
  source "${PROD_DIR}/services/config.sh"
else
  [ "$SLOT_KIND" = application ] || fail 'Invalid application slot kind'
  control_plane_environment
fi

case "$SERVICE" in
  authorization|user|team|channel|project|roadmap|notification|preference|chat|voice)
    # The operator must stage the exact desired policy. The active old policy
    # remains until the protected container cutover; release code never runs as root.
    python3 "${PROD_DIR}/slots.py" firewall "$DEPLOY_TARGET" |
      sudo -n /usr/bin/python3 -I /usr/local/libexec/cowork-backend-firewall.py check-policy ||
      fail 'Backend ingress policy is missing, inactive, or differs from this slot; apply the reviewed VM policy first'
    source "${PROD_DIR}/lib/backend-firewall.sh"
    prepare_backend_firewall
    ;;
esac

# The image owns translation from canonical fields to framework-specific settings.
while IFS= read -r -d '' entry; do
  RUN_ARGS+=(-e "$entry")
done < "${DEPLOY_SETTINGS_DIR}/container.env0"

if [ "$ACTION" = check ]; then
  printf '[deploy] %s: image=%s profile=%s bind=%s:%s container-port=%s advertise=%s:%s health=%s timeout=%ss\n' \
    "$SERVICE" "$IMAGE" "$APP_CONFIG_PROFILE" "$BIND_IP" "$HOST_PORT" "$CONTAINER_PORT" "$ADVERTISE_IP" "$HOST_PORT" "$HEALTH_PATH" "$HEALTH_TIMEOUT_SECONDS"
  exit
fi

ghcr_login_if_needed
docker pull "$IMAGE"
# Tags are discovery inputs only. Run the digest that was actually fetched.
IMAGE=$(docker image inspect "$IMAGE" --format '{{json .RepoDigests}}' | python3 -c '
import json,sys
prefix=sys.argv[1].rsplit(":",1)[0] + "@sha256:"
matches=[value for value in json.load(sys.stdin) if value.startswith(prefix)]
if len(matches) != 1: raise SystemExit("Expected exactly one fetched repository digest")
print(matches[0])' "$IMAGE")
deploy_container_safely "$CONTAINER" "$HOST_PORT" "$CONTAINER_PORT" "$HEALTH_PATH" "$IMAGE" -- "${RUN_ARGS[@]}"
