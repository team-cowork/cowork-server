#!/bin/bash
require_env LOG_HOST LOKI_PUSH_URL
COMPOSE=(docker compose --project-directory "$RELEASE_ROOT" --env-file /dev/null
  -p cowork-log-agent -f "${PROD_DIR}/log-agent/compose.yaml")
"${COMPOSE[@]}" config --quiet
[ "$ACTION" = deploy ] || { echo '[log-agent] Configuration valid'; return; }
"${COMPOSE[@]}" pull
"${COMPOSE[@]}" up -d --wait --wait-timeout 120
