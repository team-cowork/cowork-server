---
name: write-release-pr
description: Open the production release PR from develop to main. Creates the PR titled vYYYYMMDD.n, labeled release:릴리즈, and filled from PULL_REQUEST_TEMPLATE.md. Prod CD allocates and stamps changed module versions after the merge. Use when deploying develop to main; use write-pr for feature PRs.
allowed-tools: Bash(git *:*), Bash(gh *:*), Bash(cat *:*), Read, Write
---

Same flow as `write-pr` (gather → body file → preview → create → cleanup), with a fixed title and label and `develop` → `main` as head/base. Do not edit module versions or push a version commit; Prod CD owns version allocation, stamping, and the final push to `main`.

## Step 1 — Check Preconditions

```bash
git fetch origin --tags --quiet
git branch --show-current
git status -sb
gh pr list --base main --head develop --state open --json number,url
```

Stop and report when any of these hold:
- The current branch is not `develop`, or it is behind/ahead of `origin/develop`, or the working tree is not clean.
- An open `develop` → `main` PR already exists (report its URL).

## Step 2 — Gather Context

```bash
git log --first-parent --format='%h %s' origin/main..origin/develop
git log --no-merges --format='%h %s' origin/main..origin/develop
git tag --list 'v*'
cat .github/PULL_REQUEST_TEMPLATE.md
```

Collect the included PR numbers from the `Merge pull request #N` lines and read their titles with `gh pr view N --json title`. Commits pushed straight to `develop` have no PR; note them from the second log. If there is nothing between `origin/main` and `origin/develop`, stop: there is nothing to release.

## Step 3 — Write Body & Show Preview

**Title** — `v{RELEASE_VERSION}` (e.g., `v20261019.0`). No scope or description. Use the user's requested version when provided; otherwise use today's date in Asia/Seoul and one more than the largest existing tag sequence for that date, starting at 0. Set `RELEASE_VERSION` to that value without the `v` prefix. This only selects the PR title: do not create a tag or change source files. Prod CD independently assigns the actual release version after merge, so the title can differ if the date changes or another release reserves a version first. CI checks the title format without comparing it to a VERSION file or module versions.

**Label** — `release:릴리즈`.

**Body** — Follow the `.github/PULL_REQUEST_TEMPLATE.md` structure:
- `## 개요`: one sentence naming the range, e.g. "#530 ~ #532 사이의 변경사항을 상용환경에 반영합니다." or "#521 의 변경사항을 …" for a single PR.
- `## 본문`: a flat list of the included PRs as `- #N PR 제목`, then direct commits as `- 커밋 메시지` when any. Add a line for merge conditions only when the user mentions one (e.g., a PR that must land in `develop` first).
- Korean 합쇼체, no emojis, max 2500 characters, technical identifiers in backticks.

Write the body to `PR_BODY.md`, then display:

```
## PR 제목 / 라벨
- v{RELEASE_VERSION} / release:릴리즈

## PR 본문 미리보기
[body content]
```

## Step 4 — Create PR

Then create the PR. If `gh label list` does not contain `release:릴리즈`, drop `--label` and create it unlabeled; never create or swap the label.

```bash
gh pr create --base main --head develop --title "v${RELEASE_VERSION}" --body-file PR_BODY.md --label "release:릴리즈"
```

Display the PR URL. If the label was dropped, say so in one line. Cleanup: remove `PR_BODY.md`.

After the merge, Prod CD reserves the tag, stamps only selected image modules, deploys them, pushes their version fields to `main`, and publishes the GitHub Release. No root VERSION file or local bump command is used. Release notes for it are written with the `write-release-notes` skill.
