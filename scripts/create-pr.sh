#!/usr/bin/env bash
# Usage: bash scripts/create-pr.sh "<title>" "<body-file>" "<label1>,<label2>"
set -euo pipefail

TITLE="${1:?title required}"
BODY_FILE="${2:?body file required}"
LABELS="${3:-}"

if ! command -v gh &>/dev/null; then
  echo "[ERROR] gh CLI가 설치되어 있지 않습니다." >&2
  exit 1
fi

BASE_BRANCH=$(git symbolic-ref refs/remotes/origin/HEAD 2>/dev/null | sed 's|refs/remotes/origin/||' || echo "develop")
CURRENT_BRANCH=$(git branch --show-current)

if [[ "$CURRENT_BRANCH" == "$BASE_BRANCH" ]]; then
  echo "[ERROR] 현재 브랜치($CURRENT_BRANCH)는 베이스 브랜치와 같습니다. feature 브랜치에서 실행하세요." >&2
  exit 1
fi

# 원격에 브랜치가 없으면 push
if ! git ls-remote --exit-code origin "$CURRENT_BRANCH" &>/dev/null; then
  echo "[INFO] 원격에 브랜치가 없어 push합니다: $CURRENT_BRANCH"
  git push -u origin "$CURRENT_BRANCH"
fi

LABEL_ARGS=()
if [[ -n "$LABELS" ]]; then
  IFS=',' read -ra LABEL_ARRAY <<< "$LABELS"
  for label in "${LABEL_ARRAY[@]}"; do
    LABEL_ARGS+=(--label "$label")
  done
fi

gh pr create \
  --title "$TITLE" \
  --body-file "$BODY_FILE" \
  --base "$BASE_BRANCH" \
  "${LABEL_ARGS[@]}"
