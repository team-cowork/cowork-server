---
name: write-release-pr
description: Open the production release PR from develop to main. Runs make bump, commits and pushes the version bump to develop after one confirmation, then creates the PR titled with the new version, labeled release:릴리즈, and filled from PULL_REQUEST_TEMPLATE.md. Use when deploying develop to main; use write-pr for feature PRs.
allowed-tools: Bash(git *:*), Bash(make bump:*), Bash(bash scripts/bump.sh:*), Bash(gh *:*), Bash(cat *:*), Read, Write, AskUserQuestion
---

Same flow as `write-pr` (gather → body file → preview → confirm → create → cleanup), with three differences: the version is bumped first, the title and label are fixed, and the head/base are always `develop` → `main`.

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
cat .github/PULL_REQUEST_TEMPLATE.md
```

Collect the included PR numbers from the `Merge pull request #N` lines and read their titles with `gh pr view N --json title`. Commits pushed straight to `develop` have no PR; note them from the second log. If there is nothing between `origin/main` and `origin/develop`, stop: there is nothing to release.

## Step 3 — Bump the Version

```bash
make bump
```

If `make` is unavailable, run `bash scripts/bump.sh` instead (the `bump` target runs only that script). Then:

```bash
cat VERSION
git diff --name-only
```

Only version-bearing files may change: `VERSION`, `cowork-*/build.gradle.kts`, `cowork-*/pom.xml`, `cowork-*/package.json`, `cowork-*/package-lock.json`, `cowork-user/src/CoworkUser.csproj`, and the Go `main.go` files listed in `scripts/bump.sh`. Any other change means stop and report. If nothing changed, the version is already current; skip the commit in Step 5.

## Step 4 — Write Body & Show Preview

**Title** — `v{VERSION}` (e.g., `v20261016.1`). No scope, no description.

**Label** — `release:릴리즈`.

**Body** — Follow the `.github/PULL_REQUEST_TEMPLATE.md` structure:
- `## 개요`: one sentence naming the range, e.g. "#530 ~ #532 사이의 변경사항을 상용환경에 반영합니다." or "#521 의 변경사항을 …" for a single PR.
- `## 본문`: a flat list of the included PRs as `- #N PR 제목`, then direct commits as `- 커밋 메시지` when any. Add a line for merge conditions only when the user mentions one (e.g., a PR that must land in `develop` first).
- Korean 합쇼체, no emojis, max 2500 characters, technical identifiers in backticks.

Write the body to `PR_BODY.md`, then display:

```
## 릴리즈 버전
- v{VERSION} (변경 파일 N개)

## PR 제목 / 라벨
- v{VERSION} / release:릴리즈

## PR 본문 미리보기
[body content]
```

Use AskUserQuestion to confirm once: commit and push the version bump directly to `develop`, then create the PR. This direct push is the team's release convention; it never applies to other work. Wait for the answer before proceeding.

## Step 5 — Commit, Push, and Create PR

```bash
git add -u
git commit -m "update(global): v{VERSION} 릴리즈 버전 갱신"
git push origin HEAD:refs/heads/develop
```

Then create the PR. If `gh label list` does not contain `release:릴리즈`, drop `--label` and create it unlabeled; never create or swap the label.

```bash
gh pr create --base main --head develop --title "v{VERSION}" --body-file PR_BODY.md --label "release:릴리즈"
```

Display the PR URL. If the label was dropped, say so in one line. Cleanup: remove `PR_BODY.md`.

Do not run `make tag` or `make release`: after the merge, the Prod CD workflow creates the tag and GitHub Release. Release notes for it are written with the `write-release-notes` skill.
