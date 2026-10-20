#!/bin/bash
set -e

TITLE="${1:?Error: Issue title is required. Usage: create-issue.sh <title> <body-file> [label]}"
BODY_FILE="${2:?Error: Body file is required. Usage: create-issue.sh <title> <body-file> [label]}"
LABEL_INPUT="${3:-}"

if [ ! -f "$BODY_FILE" ]; then
  echo "ERROR: Body file not found: $BODY_FILE" >&2
  exit 1
fi

LABEL=$(echo "${LABEL_INPUT%%,*}" | xargs)

if [ -n "$LABEL" ]; then
  EXISTING=$(gh label list --limit 200 --json name -q '.[].name' 2>/dev/null || true)
  if [ -n "$EXISTING" ] && ! printf '%s\n' "$EXISTING" | grep -Fxq "$LABEL"; then
    echo "WARNING: Label '$LABEL' does not exist in this repository. Creating the issue without a label." >&2
    LABEL=""
  fi
fi

ARGS=(gh issue create --title "$TITLE" --body-file "$BODY_FILE")
[ -n "$LABEL" ] && ARGS+=(--label "$LABEL")

echo "Creating issue..."
echo "  Title : $TITLE"
echo "  Label : ${LABEL:-(none)}"
echo ""

if ! "${ARGS[@]}"; then
  if [ -n "$LABEL" ]; then
    echo "" >&2
    echo "WARNING: Issue creation failed with label '$LABEL'. Retrying without the label." >&2
    gh issue create --title "$TITLE" --body-file "$BODY_FILE"
  else
    exit 1
  fi
fi
