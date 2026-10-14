#!/bin/bash
# Config Server owns an explicit control-plane capability, independent of its runtime.
[ "$SLOT_KIND" = config ] || fail 'Config Server requires the config slot capability'
require_env CONFIG_SERVER_ACCOUNTS_JSON VAULT_EXTERNAL_HOST KAFKA_BOOTSTRAP_SERVERS LIVEKIT_URL LIVEKIT_WS_URL
require_env PUBLIC_API_BASE_URL S3_BUCKET
python3 "${PROD_DIR}/storage/settings.py"
require_env VAULT_ROLE_ID VAULT_SECRET_ID CONFIG_SERVER_URL EUREKA_SERVER_URL CONFIG_ALLOWED_CIDRS
require_env PUBLIC_WEB_ORIGINS PUBLIC_API_BASE_URL COWORK_GITHUB_APP_SERVICE_URL
[[ "$VAULT_AUTHENTICATION" = APPROLE && "$VAULT_SCHEME" = https ]] || fail 'Production Config requires HTTPS AppRole authentication'
python3 "${PROD_DIR}/config/validate-config-vault-token.py"
HEALTH_BASE_URL="${CONFIG_SERVER_URL%/}"
source "${PROD_DIR}/lib/config-firewall.sh"
prepare_config_firewall
