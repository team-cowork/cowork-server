---
name: write-issue
description: Use when turning follow-up work, review findings, deferred scope, or a reported bug into a GitHub issue. Covers template choice, title scope, body writing rules, and creation after user confirmation. Replaces the removed todo-docs TODO documents.
allowed-tools: Bash(git *:*), Bash(gh issue *:*), Bash(cat *:*), Read, Write
---

## Step 1 — Gather Context

```bash
cat .github/ISSUE_TEMPLATE/*.md
gh issue list --state all --limit 100 --search "<keywords>"
```

- Check the current code before writing. Drop work that is already done. When only part of it remains, narrow the issue to that part.
- Verify every path, key, class, and endpoint you will mention.
- When a matching issue already exists, report it instead of creating a duplicate.

## Step 2 — Choose the Template

| Template | When |
|---|---|
| `.github/ISSUE_TEMPLATE/Bug.md` | An observed malfunction with a reproduction path |
| `.github/ISSUE_TEMPLATE/ToDo.md` | Everything else: planned work, follow-ups, decisions to make |

## Step 3 — Title

Format: `[scope] description`.

- Scope: one domain, following `.agents/skills/write-pr/references/commit-conventions.md`. When the work spans several domains, use `[global]`; for deployment or CI work, use `[ci/cd]`. Never join scopes (`[chat·notification]`).
- Description: plain Korean that says what changes or what goes wrong, readable without knowing the code. Avoid identifiers unless no plain word exists. Max 50 characters, no emojis.

## Step 4 — Body

Read the chosen template file and keep its headings, `---` lines, and order. Replace each `>` guidance line with content. Do not add or rename sections. Leave out the frontmatter.

`.github/ISSUE_TEMPLATE/ToDo.md`:
- `## 개요`: start with the current state as fact, then why it is insufficient. Follow with `- [ ]` tasks written as actions (`~한다`), then `**완료 조건**:` written as resulting state (`~되어 있다`, `~하지 않는다`).
- `## 기타 사항`: the source (PR, review, removed document), open decisions as an options table, and related issues. Write `없음` when there is nothing.

`.github/ISSUE_TEMPLATE/Bug.md`: fill all four sections. Leave out secrets and personal data.

Writing style:
- Plain declarative `~한다`. Never `~하세요` or `~해야 합니다`.
- Backtick paths, config keys, endpoints, commands, and identifiers.
- Assert only what you verified. Write `아직 확인하지 않았다` for the rest.
- Leave out manual production checks unless asked. An issue holds work to do, not routine verification.

## Step 5 — Preview and Confirm

Write each body to `ISSUE_BODY.md` (number the files when there are several). Then show:

```
## 이슈 미리보기
- 템플릿: ToDo
- 제목: [scope] description

[body]
```

When there are several issues, show a title table first, then each body. Ask the user to confirm before creating. Creating an issue publishes it to the repository.

## Step 6 — Create

```bash
gh issue create --title "<confirmed-title>" --body-file ISSUE_BODY.md
```

- Templates assign no label. Add `--label` only when the user names one, and never create a label.
- Show each issue URL, then remove the `ISSUE_BODY*.md` files.
