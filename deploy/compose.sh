#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-}"
[ "$#" -gt 0 ] && shift
case "$MODE" in
  local)
    exec docker compose --project-directory "$ROOT" --env-file "${COMPOSE_ENV_FILE:-${ROOT}/.env}" \
      -f "$ROOT/docker-compose.yml" "$@"
    ;;
  single-vm-prod)
    : "${COMPOSE_ENV_FILE:?Set COMPOSE_ENV_FILE to an absolute production env file path}"
    : "${COMPOSE_PROJECT_NAME:?Set COMPOSE_PROJECT_NAME explicitly to preserve existing volumes}"
    exec docker compose --project-directory "$ROOT" --env-file "$COMPOSE_ENV_FILE" \
      -p "$COMPOSE_PROJECT_NAME" -f "$ROOT/deploy/compose/stack.yaml" \
      -f "$ROOT/deploy/compose/single-vm.prod.yaml" "$@"
    ;;
  *) echo "Usage: $0 <local|single-vm-prod> <compose arguments...>" >&2; exit 1 ;;
esac
