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
  *) echo "Usage: $0 local <compose arguments...>" >&2; exit 1 ;;
esac
