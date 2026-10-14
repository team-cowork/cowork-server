#!/bin/bash
# Pull/create before stopping the old container. Keep it for rollback after cutover.
# A service may override these hooks for host state that must follow its container.
before_container_cutover() { :; }
rollback_container_cutover() { :; }
finalize_container_cutover() { :; }

wait_for_health() {
  # Snapshot republication alone can take 300s; allow a VM-specific readiness budget.
  local url="$1" timeout="${2:-$HEALTH_TIMEOUT_SECONDS}" remaining request_timeout
  local deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    remaining=$((deadline - SECONDS))
    request_timeout=$((remaining < 5 ? remaining : 5))
    if curl --fail --silent --show-error --connect-timeout 2 --max-time "$request_timeout" "$url" >/dev/null 2>&1; then
      return 0
    fi
    remaining=$((deadline - SECONDS))
    ((remaining > 0)) || break
    sleep "$((remaining < 2 ? remaining : 2))"
  done
  return 1
}

deploy_container_safely() (
  set -euo pipefail
  local container="$1" host_port="$2" container_port="$3" health_path="$4" image="$5"
  shift 5
  if [ "${1:-}" = -- ]; then shift; fi
  local candidate="${container}-candidate" previous="${container}-previous"
  local old_exists=false old_was_healthy=false swapping=false candidate_ready=false complete=false candidate_id old_id
  local previous_health_base previous_health_path previous_health_timeout previous_stop_timeout capture_pid startup_log
  local log_dir="${DEPLOY_STATE_DIR:-${HOME}/.local/state/cowork}/logs"
  local health_host="$BIND_IP"
  [ "$health_host" != 0.0.0.0 ] || health_host=127.0.0.1

  # A previous interrupted deployment must be inspected, never silently deleted.
  for name in "$candidate" "$previous"; do
    if docker container inspect "$name" >/dev/null 2>&1; then
      echo "[deploy] Resolve retained container ${name} before redeploying" >&2
      exit 1
    fi
  done
  if old_id=$(docker container inspect -f '{{.Id}}' "$container" 2>/dev/null); then
    old_exists=true
    previous_health_base=$(docker container inspect -f '{{index .Config.Labels "cowork.health-base-url"}}' "$container")
    [ -n "$previous_health_base" ] || previous_health_base="http://${health_host}:${host_port}"
    previous_health_path=$(docker container inspect -f '{{index .Config.Labels "cowork.health-path"}}' "$container")
    [ -n "$previous_health_path" ] || previous_health_path="$health_path"
    previous_health_timeout=$(docker container inspect -f '{{index .Config.Labels "cowork.health-timeout-seconds"}}' "$container")
    [[ "$previous_health_timeout" =~ ^[1-9][0-9]{0,3}$ ]] || previous_health_timeout="$HEALTH_TIMEOUT_SECONDS"
    previous_stop_timeout=$(docker container inspect -f '{{index .Config.Labels "cowork.stop-timeout-seconds"}}' "$container")
    [[ "$previous_stop_timeout" =~ ^[1-9][0-9]{0,2}$ ]] || previous_stop_timeout=30
    # 배포 전부터 비정상인 컨테이너는 롤백 때 readiness를 기다려도 회복되지 않는다.
    if curl --fail --silent --connect-timeout 2 --max-time 5 "${previous_health_base}${previous_health_path}" >/dev/null 2>&1; then
      old_was_healthy=true
    fi
  fi

  # Invoked by EXIT/INT/TERM traps below.
  # shellcheck disable=SC2329
  rollback() {
    local status=$? failure_log firewall_restored=true
    trap - EXIT INT TERM
    if [ -n "${capture_pid:-}" ]; then
      kill "$capture_pid" 2>/dev/null || true
      wait "$capture_pid" 2>/dev/null || true
    fi
    if [ "$complete" = false ]; then
      if [ "$candidate_ready" = true ]; then
        # Cleanup may already have removed the old container. Keep the healthy
        # candidate and cutover policy; report failure instead of false success.
        echo '[deploy] Candidate is healthy, but cutover finalization failed; retained state needs operator attention' >&2
        exit 1
      fi
      if [ -n "${candidate_id:-}" ]; then
        # 후보 로그는 CI 로그에 노출될 수 있는 비밀을 포함할 수 있어 VM 파일로만 보존한다.
        failure_log="${log_dir}/${container}-last-failure.log"
        if (umask 077 && ulimit -f 20480 && docker logs --timestamps --tail 200 "$candidate_id" >"$failure_log" 2>&1); then
          echo "[deploy] Candidate logs saved on this VM: ${failure_log}" >&2
        fi
        docker rm -f "$candidate_id" >/dev/null || true
      fi
      if ! rollback_container_cutover; then
        firewall_restored=false
        status=1
        # Fail closed if the previous ingress policy cannot be recovered.
        if [ "$old_exists" = true ]; then docker stop --time "$previous_stop_timeout" "$old_id" >/dev/null || true; fi
        echo '[deploy] Host policy restoration failed; previous container will not be started' >&2
      fi
      if [ "$swapping" = true ] && [ "$old_exists" = true ]; then
        if docker container inspect "$previous" >/dev/null 2>&1; then
          docker rename "$previous" "$container" || true
        fi
        if [ "$firewall_restored" = false ]; then
          echo '[deploy] Previous container retained; firewall restoration needs operator attention' >&2
        elif [ "$old_was_healthy" = true ]; then
          if docker start "$old_id" >/dev/null && wait_for_health "${previous_health_base}${previous_health_path}" "$previous_health_timeout"; then
            echo "[deploy] Previous container restored" >&2
          else
            echo "[deploy] Previous container retained but restoration needs operator attention" >&2
          fi
        else
          docker start "$old_id" >/dev/null || true
          echo "[deploy] Previous container was already unhealthy before this deployment; restarted it without waiting for readiness" >&2
        fi
      fi
    fi
    exit "$status"
  }
  trap rollback EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  # Keep the initial startup separately from the final error lines. Limit each
  # file to 20 MiB and stop collection at the end of this deployment window.
  startup_log="${log_dir}/${container}-last-startup.log"
  (umask 077 && mkdir -p "$log_dir" && chmod 700 "$log_dir" &&
    touch "$startup_log" && chmod 600 "$startup_log" && : > "$startup_log")

  # Creating a container does not start a second app, run migrations or register
  # a candidate in Eureka under the old instance's address.
  candidate_id=$(docker create --name "$candidate" --restart unless-stopped \
    --label "cowork.health-base-url=${HEALTH_BASE_URL:-http://${health_host}:${host_port}}" \
    --label "cowork.health-path=${health_path}" \
    --label "cowork.health-timeout-seconds=${HEALTH_TIMEOUT_SECONDS}" \
    --label "cowork.stop-timeout-seconds=${STOP_TIMEOUT_SECONDS:-30}" \
    --label "cowork.release-sha=${DEPLOY_SHA}" \
    --label "cowork.image-digest=${image}" \
    -p "${BIND_IP}:${host_port}:${container_port}" "$@" "$image")
  before_container_cutover
  swapping=true
  if [ "$old_exists" = true ]; then
    docker stop --time "$previous_stop_timeout" "$old_id" >/dev/null
    docker rename "$old_id" "$previous"
  fi
  docker rename "$candidate_id" "$container"
  docker start "$candidate_id" >/dev/null
  (ulimit -f 20480; exec docker logs --timestamps --follow "$candidate_id") > "$startup_log" 2>&1 &
  capture_pid=$!
  echo "[deploy] Startup logs saved on this VM (up to 20 MiB): ${startup_log}"
  if ! wait_for_health "${HEALTH_BASE_URL:-http://${health_host}:${host_port}}${health_path}"; then
    echo "[deploy] ${container} failed readiness; restoring previous container" >&2
    exit 1
  fi
  candidate_ready=true
  # Only retire the old container after the new instance passes readiness.
  if [ "$old_exists" = true ]; then docker rm "$old_id" >/dev/null; fi
  finalize_container_cutover
  # Record the digest and the exact slot/secret snapshot metadata, never secret values.
  python3 - "$DEPLOY_SETTINGS_DIR/source.json" "$DEPLOY_STATE_DIR/${DEPLOY_TARGET}-runtime.json" "$image" "$candidate_id" <<'PY'
import json, os, sys, tempfile
from pathlib import Path
source, target = Path(sys.argv[1]), Path(sys.argv[2])
record = json.loads(source.read_text())
record.update(image_digest=sys.argv[3], container_id=sys.argv[4])
descriptor, temporary = tempfile.mkstemp(prefix='.runtime-', dir=target.parent)
try:
    with os.fdopen(descriptor, 'w') as output:
        os.fchmod(output.fileno(), 0o600)
        json.dump(record, output)
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, target)
finally:
    if os.path.exists(temporary): os.unlink(temporary)
PY
  complete=true
  echo "[deploy] ${container} healthy on ${BIND_IP}:${host_port}"
)

ghcr_login_if_needed() {
  if [ -n "${GHCR_READ_TOKEN:-}" ]; then
    printf '%s' "$GHCR_READ_TOKEN" | docker login ghcr.io -u "$DEPLOY_IMAGE_OWNER" --password-stdin
  fi
}
