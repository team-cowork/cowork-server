---
name: review-open-prs
description: Go through the open pull requests in the repository, except your own. Follow up on the review comments you left earlier, approve alongside a PR someone has already approved, and otherwise review the code and post inline comments that do not repeat existing ones, or approve when there is nothing to say.
compatibility: Requires git and gh (GitHub CLI) authenticated as the reviewing account
---

# review-open-prs

Use this skill only when the user asks for it by name. It posts to GitHub under their account, so do not start it on your own judgment.

Running this skill is the user's go-ahead to post review comments, replies, and approvals from their GitHub account. Do not ask for confirmation per PR. Never merge, close, push to, or edit a PR, and never request changes.

**Other people's review comments are off limits.** Do not reply to them, resolve them, judge whether they were addressed, or mention them in your own comments. You read them for one purpose only: to avoid posting the same point again. Everything this skill replies to or resolves is a comment you wrote yourself.

For each PR, in this order:

1. Follow up on the comments you left earlier (Step 2).
2. If someone has already approved the PR, approve it too and post no review (Step 3).
3. Otherwise review the code. Post comments for findings nobody has raised yet, or approve when there are none (Steps 4 to 6).

## Step 1 — Pick the PRs

```bash
git fetch origin develop
gh pr list --state open --limit 200 --json number,title,author,baseRefName,headRefOid,isDraft
gh api user --jq .login
```

The fetch matters: judge each PR against what `develop` looks like now, not against a stale local copy. The last command gives your login, the account everything is posted under.

If the user's message names PR numbers (for example `$review-open-prs 412 415`), handle only those PRs. A number that is not in the open list goes into the report as not open. With no numbers, handle every open PR.

Skip your own PRs and draft PRs. Do nothing on them, even when the user named the number, and list them in the report as skipped.

A PR is yours only when its `author` is your login. Do not look at commit authors. `pr-gatekeeper-bot` often opens PRs on a person's behalf, so the commits carry that person's name while the author is the bot. Review such a PR like any other, even when the commits are yours: the team reviews bot-opened PRs, and the commit author applies the fixes.

## Step 2 — Follow up on your own comments

A review comment and the replies under it form one conversation, which GitHub calls a thread and which can be marked resolved. Collect them:

```bash
gh api graphql -F owner='{owner}' -F repo='{repo}' -F number=<number> -f query='
query($owner: String!, $repo: String!, $number: Int!) {
  repository(owner: $owner, name: $repo) {
    pullRequest(number: $number) {
      reviewThreads(first: 100) {
        nodes {
          id isResolved isOutdated path line
          comments(first: 50) { nodes { databaseId author { login } body } }
        }
      }
    }
  }
}'
```

Keep only the conversations whose **first comment is yours** and that are not resolved. Drop every other conversation here; they belong to someone else.

For each one you kept, read the code at the current head (`git fetch origin pull/<number>/head`, then `git show <headRefOid>:<path>`) and check it against what your comment asked for. The code is the evidence. `isOutdated` only says the line changed, and a reply saying it is fixed only says the author believes so.

Your comment is settled when one of these holds:

- the code no longer has the problem, whether through your suggestion or another fix that works
- the reply gives a reason the current behaviour is right, and the reason holds up when you check it against the code
- you marked the comment optional and the author declined it

Then act:

| State of the conversation | Action |
|---|---|
| Settled | Reply with one short sentence that says so, then resolve it |
| Not settled, and the last comment is someone's reply to you | Reply with what is still missing. Do not repeat your original comment. |
| Not settled, and the last comment is yours | Nothing. You are waiting for the author. |

```bash
gh api "repos/{owner}/{repo}/pulls/<number>/comments/<databaseId of your first comment>/replies" \
  -f body="<reply>"

gh api graphql -f id="<thread id>" -f query='
mutation($id: ID!) { resolveReviewThread(input: {threadId: $id}) { thread { isResolved } } }'
```

Replies follow the writing rules in Step 5.

## Step 3 — If someone has approved, approve too

```bash
gh pr view <number> --json latestReviews,author
```

`latestReviews` holds each reviewer's most recent review.

- **Yours is `APPROVED`** → you are done with this PR.
- **Another person's is `CHANGES_REQUESTED`** → this PR cannot be approved in this run, whoever else approved it. Go to Step 4 and review; if you end up with no findings, post nothing and report it as clean but under a change request from that reviewer.
- **Another person's is `APPROVED`, and nobody's is `CHANGES_REQUESTED`** → do not review the code and do not post comments. Go straight to Step 6 and approve. The one exception: if a comment of yours is still not settled after Step 2, do not approve; report it as waiting for the author.
- **No approval** → go to Step 4.

## Step 4 — Review the code

```bash
gh pr view <number> --json title,body,files
gh pr diff <number>
```

The diff alone is often not enough to judge a change. To read the full file as the PR leaves it, or code the change calls into, fetch the PR head without checking it out:

```bash
git fetch origin pull/<number>/head
git show <headRefOid>:<path>
```

The working tree stays on its own branch, so do not open a path from the working tree to learn what the PR contains; it shows a different revision. Read and search the working tree only for code the PR does not touch, and compare against `origin/develop` when it matters whether something already exists there.

Read the `AGENTS.md` files that apply to the changed paths and the `.claude/rules/*.md` files whose `paths` match them. Those are the conventions to hold the PR to.

For Codex, respect the runtime-selected file in each directory: `AGENTS.override.md`, then `AGENTS.md`, then configured fallback filenames. Do not reapply a file superseded by that selection.

Look for things the author would want to know before merging:

- behaviour that is wrong, or that breaks on an input or state the code can really reach
- a change that breaks a caller, another module, a stored value, or a published contract
- a violation of a documented project rule
- a test that passes without checking the thing it claims to check
- a security problem (see `.claude/rules/security.md`)

Verify each finding in the code before you write it down. If you cannot point to the line and say what goes wrong, it is not a finding. Leave out style preferences that no project rule backs, and praise. A PR with no findings is a normal result; do not go looking for something to say.

### Drop what has already been said

```bash
gh api "repos/{owner}/{repo}/pulls/<number>/comments?per_page=100" --paginate \
  --jq '.[] | {user: .user.login, path, line, body}'
```

This lists every review comment on the PR: from other people, from bots, and from you on an earlier run. Remove any finding that one of them already makes, even in different words or on a nearby line. That is the only use of this list.

- Findings remain → Step 5.
- No findings remain, and every comment of yours is settled → Step 6.
- No findings remain, but a comment of yours is still not settled → post nothing; report it as waiting for the author.

## Step 5 — Post inline comments

Post all comments for one PR as a single review:

```bash
gh api "repos/{owner}/{repo}/pulls/<number>/reviews" --method POST --input - <<'EOF'
{
  "commit_id": "<headRefOid>",
  "event": "COMMENT",
  "comments": [
    { "path": "<path>", "line": <line>, "side": "RIGHT", "body": "<comment>" }
  ]
}
EOF
```

`line` is the line number in the new version of the file and must fall inside a hunk of the diff; GitHub answers 422 otherwise. When the problem sits in unchanged code, anchor the comment on the changed line that causes it. Use `"side": "LEFT"` with the old line number only for a comment on a deleted line. Leave the review-level `body` out.

Do not approve a PR in the same run that you commented on it, even if every comment is optional.

### How to write a comment

Write in Korean, in polite 합니다체, the way a teammate would say it to the author.

Each comment is one short paragraph that says only what the author needs: what the code does now, what goes wrong because of it, and what you would do instead. Two to four sentences is the usual length. The author should understand it without opening another file, and it should not take longer to read than the code it is about.

Start straight with the point. No finding numbers, no severity tags, no bold headings, no bracketed prefixes, no summary line ahead of the paragraph, no greeting or closing line. A prefix such as `[11] verifyState()가 state를 지우지 않아 같은 state를 계속 쓸 수 있습니다` is exactly what to avoid. Put identifiers, keys, and module names in backticks. If a point is optional, say so in the final sentence.

A comment about a real problem:

```
키가 서로 다른지만 확인하고 있어서, 누군가 키 문자열을 바꾸거나 항목을 빼도 이 테스트는 통과합니다. 이 키는 역할 권한으로 DB에 저장되고 `cowork-channel`, `cowork-preference`에도 사본이 있어서, 한쪽만 바뀌면 저장된 권한이 무시되거나 서비스끼리 어긋납니다. 기존 29개 키 목록을 그대로 적어 두고 `TeamRolePermission.KEYS shouldBe setOf(...)`로 고정하는 테스트를 하나 두면, 키를 바꿀 때 의도한 변경인지 리뷰에서 드러날 것 같습니다.
```

A comment about an optional improvement:

```
이제 `TeamRolePermission.KEYS`를 그대로 넘겨주는 별칭만 남았는데, 키의 출처가 두 군데로 보일 수 있을 것 같습니다. `canonicalize` 안에서 `TeamRolePermission.KEYS`를 직접 쓰고 이 상수를 지우거나, 남긴다면 카탈로그를 가리킨다는 정도의 주석을 두면 좋겠습니다. 동작에는 문제가 없어서 선택 사항입니다.
```

Replies from Step 2 follow the same rules and are shorter. For a settled comment one sentence is enough:

```
`KEYS shouldBe setOf(...)`로 고정된 것 확인했습니다.
```

For one that is not settled, say only what is still missing:

```
목록을 고정하는 테스트는 추가됐는데, `cowork-channel` 쪽 사본은 아직 예전 키를 쓰고 있어서 두 서비스가 어긋납니다.
```

## Step 6 — Approve

```bash
gh pr view <number> --json baseRefName,mergeable,mergeStateStatus,statusCheckRollup,latestReviews
```

Approve only when all of these are true. Otherwise post nothing and put the reason in the report.

| Condition | How to tell | If not |
|---|---|---|
| Nobody is requesting changes | no `latestReviews` entry with state `CHANGES_REQUESTED` | Report as under a change request, and name the reviewer |
| Base is `develop` | `baseRefName` | Report as not approved, with its base (for example a release PR into `main`) |
| No conflict with the base | `mergeable` is not `CONFLICTING` and `mergeStateStatus` is not `DIRTY` | Report as conflicting |
| No failed check | no `statusCheckRollup` entry with conclusion `FAILURE`, `TIMED_OUT`, `CANCELLED`, `ACTION_REQUIRED`, or `STARTUP_FAILURE`, or state `FAILURE` or `ERROR` | Report as failing, and name the check |
If `mergeable` is `UNKNOWN`, GitHub is still computing it: run the command once more before deciding. Checks that are still running do not block the approval; mention them in the report.

Approve with no body:

```bash
gh pr review <number> --approve
```

## Step 7 — Report

Finish with one table that covers every PR in scope, then stop.

```
| PR | 제목 | Base | 결과 |
|----|------|------|------|
| #412 | 팀 역할 권한 키 정리 | develop | 코멘트 2건 |
| #415 | 채널 목록 페이징 추가 | develop | 승인 |
| #416 | 초대 링크 만료 처리 | develop | 내 코멘트 2건 해결 처리, 승인 |
| #417 | 알림 설정 조회 API | develop | alice 승인 있음, 같이 승인 |
| #418 | 멤버 검색 조건 추가 | develop | 내 코멘트 1건 해결 처리, 1건 미해결 답글 |
| #419 | 파일 업로드 용량 제한 | develop | 새 지적 없음, 내 코멘트 1건 작성자 답변 대기 |
| #420 | v20261112.1 | main | 문제 없음, base가 develop이 아니라 승인 안 함 |
| #421 | 로그 포맷 수정 | develop | 건너뜀 (draft) |
| #422 | 워크스페이스 삭제 API | develop | 문제 없음, `build` 체크 실패로 승인 안 함 |
| #423 | 역할 조회 캐시 | develop | 건너뜀 (본인 PR) |
| #424 | 채널 권한 검사 정리 | develop | 봇이 연 PR, 코멘트 1건 |
| #425 | 메시지 검색 인덱스 | develop | 문제 없음, bob 변경 요청이 남아 있어 승인 안 함 |
```

If a command failed for a PR, put the error in that row and carry on with the others.
