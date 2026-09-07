#!/bin/bash
# 운영용 Vault 배포/유지 스크립트 (VM: cowork-db / 21108)
#
# docker/vault/docker-compose.vault.yml 기반 Vault를 앱 스택과 분리된 lifecycle로
# 띄우고, 재시작 후 매번 발생하는 unseal까지 자동화한다. 단, "최초 1회" 초기화
# (vault operator init)는 이 스크립트가 대신 하지 않는다 — unseal key/root token은
# 딱 한 번만 발급되고 어디에도 재조회할 수 없는 값이라, CD 로그에 남기지 않고
# 사람이 직접 서버에서 실행해 안전하게 보관하도록 남겨둔다.
#
# 최초 1회 수동 절차 (이 스크립트가 "initialized: false"로 종료 코드 1을 내면):
#   ssh ubuntu@ssh.gsmsv.site -p 21108
#   docker exec -it cowork-vault-prod vault operator init -key-shares=1 -key-threshold=1
#   (출력된 Unseal Key 1 / Initial Root Token을 GitHub Environment secret으로 저장)
#     gh secret set VAULT_UNSEAL_KEY --env Production --repo team-cowork/cowork-server
#     gh secret set VAULT_TOKEN --env Production --repo team-cowork/cowork-server
#   이후 재배포(또는 이 스크립트 재실행)하면 자동으로 unseal되고 시크릿 동기화까지 끝난다.
set -euo pipefail

: "${DEPLOY_SHA:?DEPLOY_SHA is required}"
: "${VAULT_EXTERNAL_HOST:?VAULT_EXTERNAL_HOST is required}"

REPO_ROOT="$HOME/cowork-server"
CONTAINER="cowork-vault-prod"

if [ ! -d "${REPO_ROOT}/.git" ]; then
  echo "[vault] cloning repo (first run on this VM)"
  git clone https://github.com/team-cowork/cowork-server.git "${REPO_ROOT}"
fi
cd "${REPO_ROOT}"
git fetch origin --quiet
git checkout "${DEPLOY_SHA}" -- docker/vault

docker network inspect cowork-server_default >/dev/null 2>&1 || docker network create cowork-server_default

echo "[vault] docker compose up -d"
VAULT_EXTERNAL_HOST="${VAULT_EXTERNAL_HOST}" \
  docker compose -f docker/vault/docker-compose.vault.yml up -d

echo "[vault] waiting for container to respond"
for _ in $(seq 1 30); do
  if docker exec "${CONTAINER}" vault status -format=json >/tmp/vault-status.json 2>/dev/null; then
    break
  fi
  sleep 2
done

INITIALIZED=$(python3 -c "import json;print(json.load(open('/tmp/vault-status.json'))['initialized'])" 2>/dev/null || echo "false")
SEALED=$(python3 -c "import json;print(json.load(open('/tmp/vault-status.json'))['sealed'])" 2>/dev/null || echo "true")

if [ "${INITIALIZED}" != "True" ] && [ "${INITIALIZED}" != "true" ]; then
  cat >&2 <<'EOF'
[vault] 이 Vault는 아직 초기화되지 않았습니다 (vault operator init 안 함).
파일 상단의 "최초 1회 수동 절차"를 서버에서 직접 실행한 뒤 다시 배포해주세요.
CD가 이 단계를 대신할 수 없습니다 — unseal key/root token은 이 시점에만 발급되고
다시 조회할 수 없는 값이라, 로그에 남기지 않고 사람이 직접 안전하게 보관해야 합니다.
EOF
  exit 1
fi

if [ "${SEALED}" = "True" ] || [ "${SEALED}" = "true" ]; then
  : "${VAULT_UNSEAL_KEY:?VAULT_UNSEAL_KEY is required to unseal an already-initialized Vault}"
  echo "[vault] unsealing"
  docker exec "${CONTAINER}" vault operator unseal "${VAULT_UNSEAL_KEY}" >/dev/null
fi

if [ -n "${VAULT_TOKEN:-}" ]; then
  echo "[vault] syncing application secrets (idempotent)"
  docker run --rm --network cowork-server_default \
    -e VAULT_ADDR="http://${CONTAINER}:8200" \
    -e VAULT_TOKEN="${VAULT_TOKEN}" \
    -e JWT_SECRET="${JWT_SECRET:-}" \
    -e MYSQL_USER="${MYSQL_USER:-}" \
    -e MYSQL_PASSWORD="${MYSQL_PASSWORD:-}" \
    -e POSTGRES_USER="${POSTGRES_USER:-}" \
    -e POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-}" \
    -e MONGO_ROOT_USERNAME="${MONGO_ROOT_USERNAME:-}" \
    -e MONGO_ROOT_PASSWORD="${MONGO_ROOT_PASSWORD:-}" \
    -e DATAGSM_CLIENT_ID="${DATAGSM_CLIENT_ID:-}" \
    -e DATAGSM_WEBHOOK_SECRET="${DATAGSM_WEBHOOK_SECRET:-}" \
    -e S3_ACCESS_KEY="${S3_ACCESS_KEY:-}" \
    -e S3_SECRET_KEY="${S3_SECRET_KEY:-}" \
    -e LIVEKIT_API_KEY="${LIVEKIT_API_KEY:-}" \
    -e LIVEKIT_API_SECRET="${LIVEKIT_API_SECRET:-}" \
    -e DISCORD_WEBHOOK_URL="${DISCORD_WEBHOOK_URL:-}" \
    -e ACCOUNT_CREDENTIAL_ENCRYPTION_KEY="${ACCOUNT_CREDENTIAL_ENCRYPTION_KEY:-}" \
    -e ACCOUNT_SHARE_OAUTH_STATE_SECRET="${ACCOUNT_SHARE_OAUTH_STATE_SECRET:-}" \
    -e GITHUB_ACCOUNT_SHARE_CLIENT_ID="${GITHUB_ACCOUNT_SHARE_CLIENT_ID:-}" \
    -e GITHUB_ACCOUNT_SHARE_CLIENT_SECRET="${GITHUB_ACCOUNT_SHARE_CLIENT_SECRET:-}" \
    -e NOTION_ACCOUNT_SHARE_CLIENT_ID="${NOTION_ACCOUNT_SHARE_CLIENT_ID:-}" \
    -e NOTION_ACCOUNT_SHARE_CLIENT_SECRET="${NOTION_ACCOUNT_SHARE_CLIENT_SECRET:-}" \
    -e JIRA_ACCOUNT_SHARE_CLIENT_ID="${JIRA_ACCOUNT_SHARE_CLIENT_ID:-}" \
    -e JIRA_ACCOUNT_SHARE_CLIENT_SECRET="${JIRA_ACCOUNT_SHARE_CLIENT_SECRET:-}" \
    -e GOOGLE_ACCOUNT_SHARE_CLIENT_ID="${GOOGLE_ACCOUNT_SHARE_CLIENT_ID:-}" \
    -e GOOGLE_ACCOUNT_SHARE_CLIENT_SECRET="${GOOGLE_ACCOUNT_SHARE_CLIENT_SECRET:-}" \
    -e FACEBOOK_ACCOUNT_SHARE_CLIENT_ID="${FACEBOOK_ACCOUNT_SHARE_CLIENT_ID:-}" \
    -e FACEBOOK_ACCOUNT_SHARE_CLIENT_SECRET="${FACEBOOK_ACCOUNT_SHARE_CLIENT_SECRET:-}" \
    -e TEAM_GITHUB_STATE_SECRET="${TEAM_GITHUB_STATE_SECRET:-}" \
    -e GITHUB_APP_SLUG="${GITHUB_APP_SLUG:-}" \
    -e GITHUB_APP_INTERNAL_API_KEY="${GITHUB_APP_INTERNAL_API_KEY:-}" \
    -v "${REPO_ROOT}/cowork-config/src/main/resources/vault/vault-init.sh:/vault-init.sh:ro" \
    hashicorp/vault:2.0.3 sh /vault-init.sh
fi

echo "[vault] healthy and unsealed"
docker exec "${CONTAINER}" vault status
