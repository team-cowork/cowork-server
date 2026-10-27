#!/usr/bin/env bash
set -euo pipefail
for ((attempt = 0; attempt < 120; attempt++)); do
  if mongosh --host 127.0.0.1 --quiet --norc "$NOMAD_TASK_DIR/initialize.js" >/dev/null 2>&1; then
    echo 'Database accounts initialized'
    exit 0
  fi
  sleep 2
done
echo 'Database account initialization failed' >&2
exit 1
