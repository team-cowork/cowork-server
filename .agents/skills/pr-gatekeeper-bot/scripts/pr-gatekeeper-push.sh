#!/usr/bin/env bash
# Usage: scripts/pr-gatekeeper-push.sh OWNER/REPO BRANCH [--dry-run]
# Pushes the local BRANCH to the same branch on OWNER/REPO as pr-gatekeeper-bot.
# Never force-pushes. Refuses integration branches, because the bot can be on ruleset bypass lists.
# The token goes to git only through environment config. It is not printed, stored, or put in a URL.
# Requires: git, base64, and the token script requirements
set -euo pipefail

repo="${1:?Usage: $0 OWNER/REPO BRANCH [--dry-run]}"
branch="${2:?Usage: $0 OWNER/REPO BRANCH [--dry-run]}"
dry_run=()
[ "${3:-}" = "--dry-run" ] && dry_run=(--dry-run)

case "$branch" in
    main|master|develop|dev|development|release|release/*|HEAD)
        echo "Refusing to push '$branch' as pr-gatekeeper-bot: push only PR head branches" >&2
        exit 1
        ;;
esac
git check-ref-format --branch "$branch" >/dev/null || { echo "Invalid branch name: $branch" >&2; exit 1; }
git rev-parse --verify --quiet "refs/heads/$branch" >/dev/null || { echo "Local branch not found: $branch" >&2; exit 1; }

token="$("$(dirname "$0")/pr-gatekeeper-token.sh" "$repo")"
[ -n "$token" ] && [ "$token" != null ] || { echo "pr-gatekeeper-bot token was not issued; nothing was pushed" >&2; exit 1; }
basic=$(printf 'x-access-token:%s' "$token" | base64 | tr -d '\n')
unset token

# Config 0 sends the bot credentials. Config 1 clears credential helpers, so git never
# falls back to (or stores into) the caller's own credentials.
GIT_TERMINAL_PROMPT=0 \
GIT_CONFIG_COUNT=2 \
GIT_CONFIG_KEY_0="http.https://github.com/.extraheader" \
GIT_CONFIG_VALUE_0="AUTHORIZATION: basic $basic" \
GIT_CONFIG_KEY_1="credential.helper" \
GIT_CONFIG_VALUE_1="" \
    git push ${dry_run[@]+"${dry_run[@]}"} "https://github.com/$repo.git" "refs/heads/$branch:refs/heads/$branch"
unset basic

[ ${#dry_run[@]} -eq 0 ] || exit 0

# Track the pushed branch through origin when origin is the same repository.
origin_url=$(git remote get-url origin 2>/dev/null || true)
origin_repo=$(sed -E 's#^(https://[^/]*github\.com/|git@github\.com:|ssh://git@github\.com/)##; s#\.git$##; s#/$##' <<<"$origin_url")
if [ "$(tr '[:upper:]' '[:lower:]' <<<"$origin_repo")" = "$(tr '[:upper:]' '[:lower:]' <<<"$repo")" ]; then
    git update-ref "refs/remotes/origin/$branch" "refs/heads/$branch"
    git branch --quiet --set-upstream-to="origin/$branch" "$branch"
    echo "Tracking origin/$branch" >&2
fi
