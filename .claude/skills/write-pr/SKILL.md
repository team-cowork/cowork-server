---
name: write-pr
description: Generate a PR title, body, and a single label from commits since the base branch, then create the PR on GitHub. Handles base branch detection, label selection, and PR creation end-to-end.
allowed-tools: Bash(git *:*), Bash(bash *create-pr.sh:*), Bash(cat *:*), Read, Write
---

## Step 1 — Gather Context

```bash
git branch --show-current
git log origin/develop..HEAD --oneline 2>/dev/null || git log --oneline -15
git diff origin/develop...HEAD --stat 2>/dev/null || git diff HEAD~5...HEAD --stat
git diff origin/develop...HEAD 2>/dev/null || git diff HEAD~5...HEAD
```

Also read the PR template:

```bash
cat .github/PULL_REQUEST_TEMPLATE.md
```

## Step 2 — Determine the Label

Read `${CLAUDE_SKILL_DIR}/references/labels.md` and select **exactly one** label based on the nature of the changes. When several labels apply, follow the priority order in that reference and keep only the top match.
Read `${CLAUDE_SKILL_DIR}/references/commit-conventions.md` for commit type and scope naming rules.

## Step 3 — Generate PR Content

**Title** — Generate 3 options in the format `[scope] description`:
- Scope: use one value from `allowedScopes` in `.github/workflows/cowork-stage-ci.yml` (`.github/workflows/cowork-prod-ci.yml` for PRs to `main`). Read the list before you write the title. Map a changed `cowork-<name>/` directory to `<name>`. Use `[global]` for several modules or repository-wide changes and `[ci/cd]` for build or deployment changes. Never shorten a value: use `[authorization]`, not `[auth]`. Do not use commit history to choose a scope.
- Description: Korean, concise, no emojis, no colons, max 50 characters total

**Body** — Follow the `.github/PULL_REQUEST_TEMPLATE.md` structure:
- Korean 합쇼체: `~하였습니다`, `~되었습니다`, `~추가하였습니다`
- No emojis
- Max 2500 characters

**Wording rules** — apply to the title and the body:
- Code names: wrap every name that exists in code or configuration in backticks, with the exact spelling from the code. This covers class, method, field, annotation, enum constant, file, module, config key, endpoint path, header, table, column, and agent names (e.g., `@Transactional`, `QueryProjectServiceImpl`, `SKILL.md`). Do not backtick general concepts; write them in Korean as described below.
- HTTP status: describe a status by its standard reason phrase in plain text, not by its number. Write `Not Found 응답을 반환하도록 수정하였습니다`, not `404 응답`. Do not write the number next to the phrase. Only when the number itself is code, such as a literal in a test or a config value, quote that code in backticks as written.
- Korean first: write general English words and concepts in Korean by meaning (e.g., retry → 재시도, fallback → 대체 처리, deprecated → 사용 중단 예정, edge case → 예외 상황). Do not transliterate a word into Hangul only to avoid English. Keep English only for code names in backticks, proper names of products, libraries, and services, common abbreviations without a settled Korean term (API, JWT, CI), and HTTP reason phrases. When the Korean term alone can be unclear, add the English term in parentheses once, at its first use.
- Punctuation: applies to the Korean title and body this skill writes, not to the English in these instructions.
  - Avoid the dash `—`. Use a comma or split the sentence instead.
  - Avoid the middle dot `·`. Use a slash `/` where possible: write `읽기/쓰기`, not `읽기·쓰기`.
  - Parentheses follow a looser rule. Do not put a long aside that breaks the flow of a sentence in parentheses; split the sentence instead. A short aside, the original term at its first use, and the expansion of an abbreviation may use parentheses.
  - Exception: `—` and `·` inside a code name in backticks or inside a fixed format stay as written.
- No checkboxes: do not use `- [ ]` or `- [x]`. Use a plain `-` list or a numbered list.

## Step 4 — Write Body & Show Preview

Write the body to `PR_BODY.md`, then display:

```
## PR 제목 후보
1. [title1]
2. [title2]
3. [title3]

## 선택된 라벨
- label

## PR 본문 미리보기
[body content]
```

Use AskUserQuestion to ask the user which title to use (present options 1/2/3). Wait for the answer before proceeding.

## Step 5 — Create PR

Run the creation script with the confirmed title and the single label:

```bash
bash "${CLAUDE_SKILL_DIR}/scripts/create-pr.sh" "<confirmed-title>" "PR_BODY.md" "<label>"
```

The script drops the label and creates the PR unlabeled when the repository does not have that
label. Never create a missing label or swap in a different one — an unlabeled PR is the expected
outcome there.

After creation, display the PR URL. If the label was dropped, say so in one line.
Cleanup: remove `PR_BODY.md`.
