#!/usr/bin/env bash
# Usage: bash scripts/create-pr.sh "<title>" "<body-file>" "<label1>,<label2>" [base-branch]
set -euo pipefail

TITLE="${1:?title required}"
BODY_FILE="${2:?body file required}"
LABELS="${3:-}"

if [[ ! -f "$BODY_FILE" ]]; then
  echo "[ERROR] PR 본문 파일($BODY_FILE)을 찾을 수 없습니다." >&2
  exit 1
fi

if ! command -v gh &>/dev/null; then
  echo "[ERROR] gh CLI가 설치되어 있지 않습니다." >&2
  exit 1
fi

BASE_BRANCH="${4:-develop}"
CURRENT_BRANCH=$(git branch --show-current)

if [[ "$CURRENT_BRANCH" == "$BASE_BRANCH" ]]; then
  echo "[ERROR] 현재 브랜치($CURRENT_BRANCH)는 베이스 브랜치와 같습니다. feature 브랜치에서 실행하세요." >&2
  exit 1
fi

echo "[INFO] 원격 브랜치를 업데이트합니다: $CURRENT_BRANCH"
git push -u origin "$CURRENT_BRANCH"

LABEL_ARGS=()
if [[ -n "$LABELS" ]]; then
  IFS=',' read -ra LABEL_ARRAY <<< "$LABELS"
  for label in "${LABEL_ARRAY[@]}"; do
    label=$(echo "$label" | xargs)
    [[ -n "$label" ]] && LABEL_ARGS+=(--label "$label")
  done
fi

gh pr create \
  --title "$TITLE" \
  --body-file "$BODY_FILE" \
  --base "$BASE_BRANCH" \
  "${LABEL_ARGS[@]}"
