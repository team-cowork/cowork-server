#!/bin/bash
set -euo pipefail
umask 077
export -n DEPLOY_GHCR_TOKEN
staging=
docker_config=
cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  if [ -n "$staging" ]; then rm -rf -- "$staging" || status=1; fi
  if [ -n "$docker_config" ]; then rm -rf -- "$docker_config" || status=1; fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
[[ "$DEPLOY_SHA" =~ ^[0-9a-f]{40}$ ]]
[[ "$DEPLOY_IMAGE_SHA" =~ ^[0-9a-f]{40}$ ]]
[[ "$DEPLOY_TOOLS_SHA" =~ ^[0-9a-f]{40}$ ]]
root="$HOME/.local/share/cowork"
cache="$root/repository.git"
release="$root/releases/$DEPLOY_SHA"
tools_release="$root/releases/$DEPLOY_TOOLS_SHA"
mkdir -p "$root/releases"
# release-retention.py takes this exclusively, so cleanup never overlaps a deployment.
exec 8>"$root/retention.lock"
flock -s -w 120 8
exec 9>"$root/source.lock"
flock -w 120 9
if [ ! -d "$cache" ]; then
  git clone --mirror https://github.com/team-cowork/cowork-server.git "$cache"
fi
archive_release() {
  local sha="$1" destination="$root/releases/$1"
  test ! -L "$destination"
  git --git-dir="$cache" fetch origin "$sha" --quiet
  if [ ! -d "$destination" ]; then
    staging="$(mktemp -d "$root/releases/.staging.XXXXXX")"
    git --git-dir="$cache" archive "$sha" | tar -x -C "$staging"
    printf '%s\n' "$sha" > "$staging/.source-sha"
    mv "$staging" "$destination"
    staging=
  fi
  test ! -L "$destination/.source-sha"
  test "$(cat "$destination/.source-sha")" = "$sha"
}
archive_release "$DEPLOY_SHA"
if [ "$DEPLOY_TOOLS_SHA" != "$DEPLOY_SHA" ]; then
  archive_release "$DEPLOY_TOOLS_SHA"
fi
flock -u 9
# Never reuse or modify the VM's persistent Docker authentication.
docker_config="$(mktemp -d "$root/.docker-auth.XXXXXX")"
chmod 700 "$docker_config"
export DOCKER_CONFIG="$docker_config"
unset DOCKER_AUTH_CONFIG
# An explicit auth entry prevents automatic use of a host credential store.
printf '%s\n' '{"auths":{"ghcr.io":{}}}' > "$DOCKER_CONFIG/config.json"
chmod 600 "$DOCKER_CONFIG/config.json"
if [ "$DEPLOY_ACTION" = deploy ] && [ -n "${DEPLOY_GHCR_TOKEN:-}" ]; then
  printf '%s' "$DEPLOY_GHCR_TOKEN" | docker login ghcr.io \
    --username "$DEPLOY_GHCR_USER" --password-stdin
fi
unset DEPLOY_GHCR_TOKEN DEPLOY_GHCR_USER
printf '%s' "$DEPLOY_PAYLOAD" | python3 "$tools_release/deploy/prod/cd/apply-settings.py" \
  "$DEPLOY_SERVICE" "$DEPLOY_TARGET" "$DEPLOY_SHA" "${DEPLOY_OWNER,,}" "$DEPLOY_ACTION" \
  --release-directory "$release" --image-sha "$DEPLOY_IMAGE_SHA"
if [ "$DEPLOY_ACTION" = deploy ]; then
  # Release the shared lock so cleanup can take it; a busy lock or a cleanup error must not fail this deploy.
  exec 8>&-
  python3 "$tools_release/deploy/prod/cd/release-retention.py" --apply \
    || echo "[retention] Cleanup skipped; run release-retention.py manually" >&2
fi
