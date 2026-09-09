#!/bin/bash
require_env VAULT_EXTERNAL_HOST VAULT_BIND_IP VAULT_DATA_VOLUME
COMPOSE=(docker compose --project-directory "$RELEASE_ROOT" --env-file /dev/null
  -p "${VAULT_COMPOSE_PROJECT:-vault}" -f "${PROD_DIR}/vault/compose.yaml")
"${COMPOSE[@]}" config --quiet
[ "$ACTION" = deploy ] || { echo '[vault] Configuration valid'; return; }
"${COMPOSE[@]}" pull
"${COMPOSE[@]}" up -d

status_file=$(mktemp)
trap 'rm -f "$status_file"' EXIT
status_ready=false
for ((attempt=0; attempt<30; attempt++)); do
  # Vault status returns 2 when sealed. A valid JSON response is still usable.
  docker exec cowork-vault-prod vault status -format=json > "$status_file" 2>/dev/null || true
  if python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert "initialized" in d and "sealed" in d' "$status_file" 2>/dev/null; then
    status_ready=true
    break
  fi
  sleep 2
done
[ "$status_ready" = true ] || fail 'Vault did not return a valid status'
initialized=$(python3 -c 'import json,sys; print(str(json.load(open(sys.argv[1]))["initialized"]).lower())' "$status_file")
sealed=$(python3 -c 'import json,sys; print(str(json.load(open(sys.argv[1]))["sealed"]).lower())' "$status_file")
[ "$initialized" = true ] || fail 'Initialize Vault interactively and securely retain its unseal keys; see docs/deployment.md'
if [ "$sealed" = true ]; then
  require_env VAULT_UNSEAL_KEY
  docker exec cowork-vault-prod vault operator unseal "$VAULT_UNSEAL_KEY" >/dev/null
fi
# Production secrets are managed in Vault, independently of container redeploys.
# The destructive development seed script must never overwrite production values.
"${COMPOSE[@]}" up -d --wait --wait-timeout 120
rm -f "$status_file"
trap - EXIT
echo '[vault] Healthy and unsealed'
