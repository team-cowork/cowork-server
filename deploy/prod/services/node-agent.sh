#!/bin/bash
require_env NODE_EXPORTER_BIND_IP
COMPOSE=(docker compose --project-directory "$RELEASE_ROOT" --env-file /dev/null
  -p cowork-node-agent -f "${PROD_DIR}/node-agent/compose.yaml")
"${COMPOSE[@]}" config --quiet
[ "$ACTION" = deploy ] || { echo '[node-agent] Configuration valid'; return; }
"${COMPOSE[@]}" pull
"${COMPOSE[@]}" up -d --wait --wait-timeout 120
