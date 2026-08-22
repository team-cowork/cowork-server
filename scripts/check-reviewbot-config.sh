#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG_FILE="$ROOT_DIR/.reviewbot/config.yml"

if [ ! -f "$CONFIG_FILE" ]; then
  echo "not found: $CONFIG_FILE" >&2
  exit 1
fi

python3 - "$CONFIG_FILE" <<'PY'
import sys
import yaml

SHARED = {"language", "minSeverity", "maxInlineComments", "include", "exclude"}
ITPLAY = {"maxFiles", "maxFileChars", "maxPromptChars", "maxOutputTokens",
          "temperature", "autoReview", "triggerPrefix"}
SANDRONE = SHARED | {"tone", "threadReply", "autoReview", "autoReviewOnPush",
                     "summaryPlacement", "maxReviewBatches"}
SEVERITY = {"critical", "major", "minor", "nit"}
TONE = {"professional", "intelligent", "polite", "sandrone"}
PLACEMENT = {"new-comment", "update-comment", "pr-body"}

path = sys.argv[1]
with open(path, encoding="utf-8") as f:
    config = yaml.safe_load(f) or {}

errors = []
warnings = []

sandrone = config.get("sandrone") or {}
if not isinstance(sandrone, dict):
    errors.append("sandrone must be a mapping")
    sandrone = {}

for key in config:
    if key not in SHARED | ITPLAY | {"sandrone"}:
        warnings.append(f"unknown top-level key: {key}")
for key in sandrone:
    if key not in SANDRONE:
        warnings.append(f"unknown sandrone key: {key}")

def check_enum(scope, key, value, allowed):
    if value is not None and value not in allowed:
        errors.append(f"{scope}{key}: {value!r} not in {sorted(allowed)}")

check_enum("", "minSeverity", config.get("minSeverity"), SEVERITY)
check_enum("sandrone.", "minSeverity", sandrone.get("minSeverity"), SEVERITY)
check_enum("sandrone.", "tone", sandrone.get("tone"), TONE)
check_enum("sandrone.", "summaryPlacement", sandrone.get("summaryPlacement"), PLACEMENT)

prefix = config.get("triggerPrefix")
if prefix is not None and not str(prefix).startswith("/"):
    errors.append(f"triggerPrefix must start with '/': {prefix!r}")

for line in warnings:
    print(f"warning: {line}")
for line in errors:
    print(f"error: {line}", file=sys.stderr)

if errors:
    sys.exit(1)
print(f"ok: {path}")
PY
