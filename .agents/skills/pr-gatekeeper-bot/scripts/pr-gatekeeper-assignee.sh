#!/usr/bin/env bash
# Usage: scripts/pr-gatekeeper-assignee.sh OWNER/REPO BASE_REF HEAD_REF
# Prints the GitHub login of the person who did the most work in BASE_REF..HEAD_REF.
# Work is the number of added plus deleted lines in non-merge commits; lock files are excluded.
# Prints nothing (exit 0) when no assignable person is found. The reason goes to stderr.
# Uses only read-only git commands and the caller's own gh authentication.
# Requires: git, gh, awk
set -euo pipefail

repo="${1:?Usage: $0 OWNER/REPO BASE_REF HEAD_REF}"
base="${2:?Usage: $0 OWNER/REPO BASE_REF HEAD_REF}"
head="${3:?Usage: $0 OWNER/REPO BASE_REF HEAD_REF}"

skip() { echo "No assignee: $*" >&2; exit 0; }

git rev-parse --verify --quiet "$base^{commit}" >/dev/null || skip "base ref '$base' is not available locally (run git fetch)"
git rev-parse --verify --quiet "$head^{commit}" >/dev/null || skip "head ref '$head' is not available locally"

# One row per author email: <lines> <commits> <latest commit sha> <email>, most work first.
ranking=$(git log --no-merges --format='@%H %ae' --numstat "$base..$head" -- \
    ':(exclude,glob)**/package-lock.json' ':(exclude,glob)**/bun.lock' ':(exclude,glob)**/bun.lockb' \
    ':(exclude,glob)**/yarn.lock' ':(exclude,glob)**/pnpm-lock.yaml' ':(exclude,glob)**/go.sum' \
    ':(exclude,glob)**/*.lock' \
  | awk '
    /^@/ { sha = substr($1, 2); email = tolower($2); commits[email]++; if (!(email in latest)) latest[email] = sha; next }
    NF >= 3 { lines[email] += ($1 == "-" ? 0 : $1) + ($2 == "-" ? 0 : $2) }
    END { for (e in commits) printf "%d %d %s %s\n", lines[e], commits[e], latest[e], e }
  ' | sort -k1,1nr -k2,2nr)

[ -n "$ranking" ] || skip "no non-merge commits in $base..$head"

echo "Work by author (lines changed, commits):" >&2
awk '{ printf "  %s: %d lines, %d commits\n", $4, $1, $2 }' <<<"$ranking" >&2

login_of() { gh api "repos/$repo/commits/$1" --jq '.author.login // empty' 2>/dev/null || true; }

rows=()
while IFS= read -r row; do rows+=("$row"); done <<<"$ranking"
for i in "${!rows[@]}"; do
    read -r lines commits sha email <<<"${rows[$i]}"
    login=$(login_of "$sha")
    [ -n "$login" ] || skip "the top author $email is not linked to a GitHub account"
    if [[ "$login" == *"[bot]" ]]; then
        echo "Skipping bot author $login" >&2
        continue
    fi
    # Another human author with the same amount of work means there is no single owner.
    for other in "${rows[@]:$((i + 1))}"; do
        read -r other_lines other_commits other_sha other_email <<<"$other"
        [ "$other_lines $other_commits" = "$lines $commits" ] || break
        other_login=$(login_of "$other_sha")
        [[ "$other_login" == *"[bot]" ]] && continue
        skip "$email and $other_email did the same amount of work"
    done
    gh api "repos/$repo/assignees/$login" >/dev/null 2>&1 || skip "$login cannot be assigned in $repo (no repository access)"
    echo "$login"
    exit 0
done

skip "every author in $base..$head is a bot"
