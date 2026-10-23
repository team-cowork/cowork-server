#!/usr/bin/env bash
# Usage: scripts/pr-gatekeeper-token.sh OWNER/REPO
# Prints an installation token for pr-gatekeeper-bot, scoped to the given repository.
# Requires: openssl, curl, jq
set -euo pipefail

client_id="${PR_GATEKEEPER_CLIENT_ID:-Iv23lidfC71X7cztqKZE}"
key_path="${PR_GATEKEEPER_PRIVATE_KEY_PATH:-$HOME/.config/pr-gatekeeper-bot/private-key.pem}"
repo="${1:?Usage: $0 OWNER/REPO}"

[ -f "$key_path" ] || { echo "Private key not found: $key_path" >&2; exit 1; }

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

now=$(date +%s)
header=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
payload=$(printf '{"iat":%d,"exp":%d,"iss":"%s"}' $((now - 60)) $((now + 540)) "$client_id" | b64url)
signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -sign "$key_path" | b64url)
jwt="$header.$payload.$signature"

api() { curl -fsS -H "Authorization: Bearer $jwt" -H "Accept: application/vnd.github+json" "$@"; }

installation_id=$(api "https://api.github.com/repos/$repo/installation" | jq -r .id)

api -X POST "https://api.github.com/app/installations/$installation_id/access_tokens" \
  -d "{\"repositories\":[\"${repo#*/}\"]}" | jq -r .token
