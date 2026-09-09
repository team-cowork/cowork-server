#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
: "${LOG_AGENT_ENV_FILE:?Set an absolute path to the VM log-agent env file}"
exec docker compose --project-directory "$ROOT" --env-file "$LOG_AGENT_ENV_FILE" \
  -p cowork-log-agent -f "$ROOT/deploy/prod/log-agent/compose.yaml" "$@"
