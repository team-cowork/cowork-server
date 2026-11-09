---
name: write-issue
description: Use when turning follow-up work, review findings, deferred scope, or a reported bug into a GitHub issue. Covers template choice, title scope, body writing rules, and creation after user confirmation.
allowed-tools: Bash(git *:*), Bash(gh issue list *), Bash(gh pr view *), Bash(gh api *), Bash(bash *create-issue.sh:*), Bash(cat *:*), Read, Write
---

## Step 1 — Gather Context

```bash
cat .github/ISSUE_TEMPLATE/*.md
gh issue list --state all --limit 100 --search "<keywords>"
```

- Check the current code before writing. Drop work that is already done. When only part of it remains, narrow the issue to that part.
- Verify every path, key, class, and endpoint you will mention.
- When a matching issue already exists, report it instead of creating a duplicate.

## Step 2 — Validate Each Candidate

Review findings and AI suggestions often describe problems that do not exist or do not matter. Try to disprove each candidate before you write it. Keep it only when every check passes. For work the user explicitly asked for, check only `Actionable`:

| Check | Pass when |
|---|---|
| Real | The current code shows the problem or gap, and you can cite `file:line`. A guess about what the code might do does not pass. |
| Reachable | An existing caller, input, or config path triggers it. "If someone later..." does not pass. |
| Impact | One sentence states what breaks, what is lost, or what costs more, and for whom. Style taste, naming preference, and "could be cleaner" do not pass. |
| Not intended | No code comment, document, commit message, or PR discussion shows the current behavior is deliberate. An explicit deferral for later work does not count as deliberate. |
| Actionable | The tasks and completion condition are concrete enough to finish and verify. |

- Drop a candidate that fails a check. Do not keep it by softening the wording to `가능성이 있다` or similar.
- When the code cannot decide a check, do not create the issue. Ask the user.
- When the user still asks for a dropped candidate, create it and state the failed check in `## 기타 사항` (Bug.md: `## 실행 환경 및 참고 자료`).

## Step 3 — Choose the Template

| Template | When |
|---|---|
| `.github/ISSUE_TEMPLATE/Bug.md` | An observed malfunction with a reproduction path |
| `.github/ISSUE_TEMPLATE/ToDo.md` | Everything else: planned work, follow-ups, decisions to make |

Then read `.agents/skills/write-issue/references/labels.md` and select **exactly one** label that matches the direction of the issue.

## Step 4 — Title

Format: `[scope] description`.

- Scope: one domain, following `.agents/skills/write-pr/references/commit-conventions.md`. When the work spans several domains, use `[global]`; for deployment or CI work, use `[ci/cd]`. Never join scopes (`[chat·notification]`).
- Description: plain Korean that says what changes or what goes wrong, readable without knowing the code. End with a noun phrase (`중복 알림 차단`, `로그 수집 누락 감지`), never a verb ending such as `~하기`, `~한다`, or `~합니다`. Avoid identifiers unless no plain word exists. Max 50 characters, no emojis, no `·` or `—`.

## Step 5 — Body

Read the chosen template file and keep its headings and order. Leave out the `---` line under each heading. Replace every guidance line or paragraph under a heading with content, whether or not it starts with `>`. Do not add or rename sections. Leave out the frontmatter.

`.github/ISSUE_TEMPLATE/ToDo.md`:
- `## 개요`: start with the current state as fact, then why it is insufficient. Follow with `- [ ]` tasks written as actions (`~한다`), then `**완료 조건**:` written as resulting state (`~되어 있다`, `~하지 않는다`).
- `## 기타 사항`: the source (PR, review, removed document), open decisions as an options table, and related issues. Write `없음` when there is nothing.

`.github/ISSUE_TEMPLATE/Bug.md`: fill all four sections. Leave out secrets and personal data.

Writing style:
- Plain declarative `~한다`. Never `~하세요` or `~해야 합니다`.
- Never use `·` or `—`. Use a comma or split the sentence instead.
- Backtick paths, config keys, endpoints, commands, and identifiers.
- Assert only what you verified. Write `아직 확인하지 않았다` for the rest.
- Leave out manual production checks unless asked. An issue holds work to do, not routine verification.

## Step 6 — Preview and Confirm

Write each body to `ISSUE_BODY.md` (number the files when there are several). Then show:

```
## 이슈 미리보기
- 템플릿: ToDo
- 제목: [scope] description
- 라벨: label

[body]
```

After the previews, list each dropped candidate with the check it failed and one line of evidence. Write `제외한 후보 없음` when there is none.

When there are several issues, show a title table first, then each body. Ask the user to confirm before creating. Creating an issue publishes it to the repository.

## Step 7 — Create

```bash
bash .agents/skills/write-issue/scripts/create-issue.sh "<confirmed-title>" "ISSUE_BODY.md" "<label>"
```

The script drops the label and creates the issue unlabeled when the repository does not have that
label. Never create a missing label or swap in a different one.

Show each issue URL. If the label was dropped, say so in one line. Then remove the `ISSUE_BODY*.md` files.
