---
name: pr-gatekeeper-bot
description: Use only when the user explicitly asks to create or merge a pull request through pr-gatekeeper-bot, so the PR is opened and merged by the bot instead of the user's personal account. Ordinary PR requests use write-pr.
---

# pr-gatekeeper-bot

Push PR head branches, and create and merge pull requests, as `pr-gatekeeper-bot[bot]`, never as the user's personal account. When possible, assign each new PR to the person who did the most work in it.

## App identity (public values)

| Item | Value |
|---|---|
| App name | `pr-gatekeeper-bot` |
| Owner organization | `it-play` |
| App ID | `5405998` |
| Client ID | `Iv23lidfC71X7cztqKZE` |
| PR author shown on GitHub | `pr-gatekeeper-bot[bot]` |

## Related files and settings

| Item | Value |
|---|---|
| Hub repository (hosts the workflows and the secret) | `tmp-it-play/pr-gatekeeper-bot` |
| Token script | `${CLAUDE_SKILL_DIR}/scripts/pr-gatekeeper-token.sh OWNER/REPO` |
| Bot gh wrapper | `${CLAUDE_SKILL_DIR}/scripts/pr-gatekeeper-gh.sh OWNER/REPO <gh args...>` |
| Bot push script (token mode) | `${CLAUDE_SKILL_DIR}/scripts/pr-gatekeeper-push.sh OWNER/REPO <head> [--dry-run]` |
| Assignee script | `${CLAUDE_SKILL_DIR}/scripts/pr-gatekeeper-assignee.sh OWNER/REPO <base-ref> <head-ref>` |
| Create workflow | `.github/workflows/pr-gatekeeper-create.yml` |
| Merge workflow | `.github/workflows/pr-gatekeeper-merge.yml` |
| Repository secret (workflow mode) | `PR_GATEKEEPER_PRIVATE_KEY` |
| Default private key path (token mode) | `~/.config/pr-gatekeeper-bot/private-key.pem` |
| Env var to override key path | `PR_GATEKEEPER_PRIVATE_KEY_PATH` |

## Target repository

The target repository (OWNER/REPO) is the repository the PR lives in. It can be any repository, in any account or organization, where pr-gatekeeper-bot is installed — not only the hub repository.

- Token mode: pass the target to the token script and to `--repo`.
- Workflow mode: always dispatch the workflow on the hub repository (`--repo tmp-it-play/pr-gatekeeper-bot`) and pass the target with `-f repo=OWNER/REPO`. If `repo` is empty, the workflow targets the hub repository itself.
- Run all read-only `gh` commands against the target with `--repo OWNER/REPO`.

## 1. Core rules

- `write-pr` takes precedence. Use this skill only when the user explicitly names pr-gatekeeper-bot (or the gatekeeper bot) for a PR; otherwise create PRs with `write-pr`.
- Once this skill is selected, create and merge PRs **only** through pr-gatekeeper-bot. Never run `gh pr create` or `gh pr merge` without the bot token — that records the action under the user's personal account.
- In token mode, push the PR head branch as the bot (section 4, step 3). A request to create a PR through this skill is consent to push its head branch; do not ask again. Never force-push, and never push integration branches (`main`, `develop`, and similar) as the bot.
- Assigning the PR is best effort. If no single person can be assigned, create the PR with no assignee and tell the user why (section 6, "Assignee").
- Merge **only after explicit user confirmation**. Before merging, show the PR number, base branch, and merge method, and wait for a yes.
- If the user does not specify a base, prefer a development integration branch such as `develop` when the repository has one (`git ls-remote --heads origin develop dev development`). If there is none, use the repository's default branch (`gh repo view --json defaultBranchRef -q .defaultBranchRef.name`). If several candidates exist or the right base is unclear, ask the user instead of choosing. Tell the user which branch the PR targets before running anything.

## 2. Mode selection

1. If the user names a mode, use it.
2. Otherwise, check whether the private key file exists (existence only — never read it):
   ```bash
   test -f "${PR_GATEKEEPER_PRIVATE_KEY_PATH:-$HOME/.config/pr-gatekeeper-bot/private-key.pem}" && echo token || echo workflow
   ```
   - File exists → **token mode**.
   - File missing → **workflow mode**.
3. Before executing, state the chosen mode in one line (e.g. "Using token mode (local private key found).").

## 3. Token handling rules (token mode)

- Run every bot command through `"${CLAUDE_SKILL_DIR}/scripts/pr-gatekeeper-gh.sh" OWNER/REPO <gh args...>`. It runs `gh` only after a token is issued.
- Push as the bot only through `"${CLAUDE_SKILL_DIR}/scripts/pr-gatekeeper-push.sh" OWNER/REPO <head>`. It gives the token to git through environment config, clears credential helpers so git cannot fall back to the user's credentials, and never puts the token in a URL or in `.git/config`. Never build a push URL or git config that contains the token yourself.
- Never use `GH_TOKEN="$(<token script>)" <command>`. When the token script fails, the substitution is empty and `gh` silently falls back to the user's personal account.
- Never `export GH_TOKEN`, never store the token in a shell variable or file, never print it.
- Never run `${CLAUDE_SKILL_DIR}/scripts/pr-gatekeeper-token.sh` on its own (its output is the token).
- Never read, cat, or print the private key file. Only check whether it exists.
- Read-only commands that do not need the bot (`gh pr list`, `gh pr view`, `gh label list`, `gh run list`, etc.) run with the user's own `gh` authentication.

## 4. Pre-flight checks (both modes)

Run these before creating a PR:

1. Confirm `gh` is authenticated:
   ```bash
   gh auth status
   ```
2. Resolve the target OWNER/REPO. If the user named a repository, use it; otherwise use the current directory's repository:
   ```bash
   gh repo view --json nameWithOwner -q .nameWithOwner
   ```
   Always use the canonical name this prints (repositories may have been renamed).
3. Confirm the head branch exists on the remote:
   ```bash
   git ls-remote --exit-code --heads origin <head>
   ```
   If it is missing (or local commits are ahead of the remote), push it. The PR request is the consent, so do not ask:
   - Token mode: push as the bot.
     ```bash
     "${CLAUDE_SKILL_DIR}/scripts/pr-gatekeeper-push.sh" OWNER/REPO <head>
     ```
     The script pushes the local `<head>` branch to the same name on OWNER/REPO without force. When `origin` is OWNER/REPO, it also sets `origin/<head>` as the upstream. The commits keep their own authors; only the push is made by the bot. If the push fails, report the cause (see section 8) and ask whether to push with the user's own git credentials instead (`git push -u origin <head>`). Do not fall back on your own.
   - Workflow mode: the bot cannot push without the local private key. Push with the user's own git credentials (`git push -u origin <head>`) and tell the user that the push was made by their account.

   If the remote branch has commits that the local branch does not have, do not push. Tell the user instead.
4. Check for an existing open PR with the same head/base:
   ```bash
   gh pr list --repo OWNER/REPO --head <head> --base <base> --state open
   ```
   If one exists, report it and ask the user how to proceed instead of creating a duplicate.
5. If labels are requested, confirm they exist:
   ```bash
   gh label list --repo OWNER/REPO --limit 200 --json name -q '.[].name'
   ```
   For any label that does not exist, tell the user and ask whether to drop it.
6. Find the assignee candidate after the head branch is on the remote:
   ```bash
   git fetch origin <base>
   "${CLAUDE_SKILL_DIR}/scripts/pr-gatekeeper-assignee.sh" OWNER/REPO origin/<base> <head>
   ```
   The script measures work as added plus deleted lines in the non-merge commits of `origin/<base>..<head>`, without lock files (`package-lock.json`, `bun.lock`, `go.sum`, `*.lock`, and similar). It prints the GitHub login of the author with the most work, or nothing. It prints nothing when the top author has no linked GitHub account, two human authors tie, every author is a bot, or the top author cannot be assigned in the repository. Its stderr shows the work per author and the reason; keep both for the report. It uses only read-only git commands and the user's own `gh` authentication.

## 5. Writing PR content

- When the repository has a `write-pr` skill, take the title, body, and label from its rules, and use this skill only to create the PR as the bot.
- If `.github/pull_request_template.md` exists, follow its structure.
  Otherwise use these sections in order: summary of changes, key changes, how to test.
- Match the language of the title and body to the repository's recent PRs, regardless of the language of this skill:
  ```bash
  gh pr list --repo OWNER/REPO --state all --limit 10 --json number,title
  ```
  If there are no PRs yet, use the language the user is writing in.
- For a multi-line body, write it to a temporary file (e.g. `mktemp`), pass it to the command, and delete the file after the command finishes.

## 6. Creating a PR

### Token mode

```bash
"${CLAUDE_SKILL_DIR}/scripts/pr-gatekeeper-gh.sh" OWNER/REPO pr create --repo OWNER/REPO --base <base> --head <head> --title "<title>" --body-file <body-file> --label "a,b"
```

Omit `--label` when there are no labels; add `--draft` for a draft PR. Report the URL printed by the command.

Do not pass `--assignee` to `pr create`. An assignee that GitHub rejects would stop the PR from being created. Assign the PR in a separate step (see "Assignee" below).

### Workflow mode

1. Record the dispatch time, then trigger the workflow:
   ```bash
   since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
   gh workflow run pr-gatekeeper-create.yml --repo tmp-it-play/pr-gatekeeper-bot -f repo=OWNER/REPO -f title="<title>" -f body="$(cat <body-file>)" -f labels="a,b" -f base=<base> -f head=<head>
   ```
   Add `-f draft=true` for a draft PR.
2. The run may not appear immediately. Wait a few seconds, then find the run created at or after the dispatch time:
   ```bash
   gh run list --repo tmp-it-play/pr-gatekeeper-bot --workflow=pr-gatekeeper-create.yml --event=workflow_dispatch --limit 5 --json databaseId,createdAt,status,displayTitle
   ```
   Pick the run whose `createdAt` is at or after `since` and whose `displayTitle` names the target repository and head/base. If none appears yet, wait and retry a few times.
3. Wait for completion:
   ```bash
   gh run watch <run-id> --repo tmp-it-play/pr-gatekeeper-bot --exit-status
   ```
4. On failure, inspect and report the cause:
   ```bash
   gh run view <run-id> --repo tmp-it-play/pr-gatekeeper-bot --log-failed
   ```

### Assignee

Use the login from section 4, step 6.

- Token mode, with a login: assign it as the bot. This REST call does not fail on a rejected login, so always verify the result afterward.
  ```bash
  "${CLAUDE_SKILL_DIR}/scripts/pr-gatekeeper-gh.sh" OWNER/REPO api -X POST repos/OWNER/REPO/issues/<number>/assignees -f 'assignees[]=<login>'
  ```
  If the call fails, leave the PR with no assignee and report the error. Do not retry with the user's own account.
- Workflow mode: the hub create workflow has no assignee input, so the bot cannot assign the PR. Leave it with no assignee and report this together with the candidate login, so that the user can assign it.
- No login: leave the PR with no assignee and report the reason from the script.

### Verify (both modes)

```bash
gh pr list --repo OWNER/REPO --head <head> --base <base> --json number,url,author,assignees
```

Report the PR number and URL. Confirm `author.is_bot` is `true` and `author.login` contains `pr-gatekeeper-bot`. If the author is not the bot, tell the user immediately.

Report the assignee result in every case:
- Assigned: the login, and the work per author that selected it.
- Not assigned: "Assignee left empty", the reason, and the candidate login if there is one.
- Report who pushed the head branch: the bot, the user's account, or nobody (already on the remote).

## 7. Merging a PR

1. Inspect the PR state and show a short summary:
   ```bash
   gh pr view <number> --repo OWNER/REPO --json state,mergeable,mergeStateStatus,reviewDecision,statusCheckRollup,baseRefName
   ```
2. Stop and tell the user (do not merge) if any of these hold:
   - review not approved where required (`reviewDecision` is `REVIEW_REQUIRED` or `CHANGES_REQUESTED`)
   - a check has failed
   - there are conflicts (`mergeable` is `CONFLICTING` or `mergeStateStatus` is `DIRTY`)

   If checks are still running, ask whether to schedule auto-merge (`--auto` / `-f auto=true`).
3. Show the PR number, base, and merge method (default `merge`, delete branch `true`) and get explicit confirmation.
4. Execute:
   - Token mode:
     ```bash
     "${CLAUDE_SKILL_DIR}/scripts/pr-gatekeeper-gh.sh" OWNER/REPO pr merge <number> --repo OWNER/REPO --merge --delete-branch
     ```
   - Workflow mode:
     ```bash
     since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
     gh workflow run pr-gatekeeper-merge.yml --repo tmp-it-play/pr-gatekeeper-bot -f repo=OWNER/REPO -f pr=<number> -f method=merge -f delete_branch=true
     ```
     Then find and watch the run exactly as in section 6, using `--workflow=pr-gatekeeper-merge.yml`.
5. Verify and report:
   ```bash
   gh pr view <number> --repo OWNER/REPO --json state,mergedBy
   ```

## 8. Common errors

- **Token issuance fails (token mode).** Check in this order:
  1. Key file path is wrong or the file is missing (check `PR_GATEKEEPER_PRIVATE_KEY_PATH`).
  2. The app is not installed on the repository (the `/repos/OWNER/REPO/installation` call returns 404). Tell the user to ask a repository admin to install pr-gatekeeper-bot.
  3. Local clock skew (JWT `iat`/`exp` validation fails). Ask the user to sync the system clock.

  If token mode fails, tell the user and ask whether to switch to workflow mode.
- **Workflow not found.** Check that `--repo tmp-it-play/pr-gatekeeper-bot` was passed; otherwise the workflow file is most likely not on the hub repository's default branch.
- **Token step fails inside the workflow.** Ask the user to confirm the secret `PR_GATEKEEPER_PRIVATE_KEY` is set on the hub repository and that the app is installed on the target repository's account or organization with access to that repository.
- **Permission error on merge.** The app lacks Contents write permission, or the PR touches workflow files and the app lacks Workflows permission.
- **Merge rejected by branch protection / rulesets.** Required reviews are not satisfied, or the app is not on the ruleset bypass list. Note that the bot cannot approve a PR it authored.
- **`--auto` fails.** "Allow auto-merge" is disabled in the repository settings.
- **Bot push fails.** Common causes: the app lacks Contents write permission; the branch changes `.github/workflows/` files and the app lacks Workflows permission; a ruleset blocks the branch name; the remote branch has commits the local branch does not have (rejected as non-fast-forward). Report the git error and ask the user whether to push with their own credentials. Never retry with `--force`.
- **Assignment is ignored.** The REST call succeeds but `assignees` stays empty when the login has no access to the repository, or when the app lacks Issues or Pull requests write permission. Report "Assignee left empty" with the candidate login.
- **No matter what fails, never fall back to `gh pr create`, `gh pr merge`, or a push with the user's credentials on your own.** Report the cause and wait for the user's decision.
