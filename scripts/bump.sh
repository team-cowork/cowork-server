#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VERSION_FILE="$ROOT_DIR/VERSION"

DATE=$(date +"%Y%m%d")
EXISTING=$(git -C "$ROOT_DIR" tag -l "v${DATE}.*" 2>/dev/null | wc -l | tr -d ' ')
NEW_VERSION="${DATE}.${EXISTING}"

printf '%s' "${NEW_VERSION}" > "$VERSION_FILE"

GRADLE_FILES=(
  "cowork-user/build.gradle.kts"
  "cowork-gateway/build.gradle.kts"
  "cowork-team/build.gradle.kts"
  "cowork-config/build.gradle.kts"
  "cowork-notification/build.gradle.kts"
  "cowork-preference/build.gradle.kts"
)

for FILE in "${GRADLE_FILES[@]}"; do
  sed -i '' "s/^version = \".*\"/version = \"${NEW_VERSION}\"/" "$ROOT_DIR/$FILE"
done

sed -i '' "s/\"version\": \".*\"/\"version\": \"${NEW_VERSION}\"/" "$ROOT_DIR/cowork-chat/package.json"

echo "Version set to v${NEW_VERSION}"
