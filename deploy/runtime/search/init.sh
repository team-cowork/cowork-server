#!/usr/bin/env bash
set -euo pipefail

ready=false
for ((attempt = 0; attempt < 120; attempt++)); do
  if curl --config "$NOMAD_SECRETS_DIR/admin.curl" --silent --fail --max-time 5 --output /dev/null \
      'http://127.0.0.1:9200/_cluster/health?wait_for_status=yellow&timeout=3s'; then
    ready=true
    break
  fi
  sleep 2
done
if [[ "$ready" != true ]]; then
  echo 'Search administrator connection did not become ready' >&2
  exit 1
fi
if ! curl --config "$NOMAD_SECRETS_DIR/admin.curl" --silent --fail --max-time 30 --output /dev/null \
    --request PUT --data-binary "@$NOMAD_TASK_DIR/role.json" \
    http://127.0.0.1:9200/_security/role/cowork_chat_search; then
  echo 'Search role initialization failed' >&2
  exit 1
fi
if ! curl --config "$NOMAD_SECRETS_DIR/admin.curl" --silent --fail --max-time 30 --output /dev/null \
    --request PUT --data-binary "@$NOMAD_SECRETS_DIR/user.json" \
    http://127.0.0.1:9200/_security/user/cowork_chat; then
  echo 'Search user initialization failed' >&2
  exit 1
fi
if ! curl --config "$NOMAD_SECRETS_DIR/application.curl" --silent --fail --max-time 10 --output /dev/null \
    http://127.0.0.1:9200/_security/_authenticate; then
  echo 'Search application authentication failed' >&2
  exit 1
fi
echo 'Search account initialized'
