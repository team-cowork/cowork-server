#!/usr/bin/env bash
# Usage: scripts/pr-gatekeeper-gh.sh OWNER/REPO <gh arguments...>
# Runs gh as pr-gatekeeper-bot. Exits without running gh when no token is issued,
# because an empty GH_TOKEN makes gh fall back to the caller's own account.
set -euo pipefail

repo="${1:?Usage: $0 OWNER/REPO <gh arguments...>}"
shift
token="$("$(dirname "$0")/pr-gatekeeper-token.sh" "$repo")"
[ -n "$token" ] && [ "$token" != null ] || { echo "pr-gatekeeper-bot token was not issued; gh was not run" >&2; exit 1; }
GH_TOKEN="$token" exec gh "$@"
