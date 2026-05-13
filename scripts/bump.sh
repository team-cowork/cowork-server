#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VERSION_FILE="$ROOT_DIR/VERSION"

DATE=$(date +"%Y%m%d")
EXISTING=$(git -C "$ROOT_DIR" tag -l "v${DATE}.*" 2>/dev/null | wc -l | tr -d ' ')
NEW_VERSION="${DATE}.${EXISTING}"

printf '%s' "${NEW_VERSION}" > "$VERSION_FILE"

for FILE in "$ROOT_DIR"/cowork-*/build.gradle.kts; do
  perl -i -pe "s/^version = \".*\"/version = \"${NEW_VERSION}\"/" "$FILE"
done

perl -i -pe "s/\"version\": \".*\"/\"version\": \"${NEW_VERSION}\"/" "$ROOT_DIR/cowork-chat/package.json"

perl -i -pe "s/(\s+version: \").*\"/\${1}${NEW_VERSION}.0\"/" "$ROOT_DIR/cowork-user/mix.exs"

for FILE in \
  "$ROOT_DIR/cowork-authorization/cmd/main.go" \
  "$ROOT_DIR/cowork-notification/cmd/server/main.go" \
  "$ROOT_DIR/cowork-voice/cmd/server/main.go"; do
  perl -i -pe "s|^(// \@version\s+)\S+|\${1}${NEW_VERSION}|" "$FILE"
done

echo "Version set to v${NEW_VERSION}"

