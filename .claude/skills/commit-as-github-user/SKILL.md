---
name: commit-as-github-user
description: Split changes into minimal coherent commits and set both author and committer to a requested GitHub user's identity for each commit, without changing Git configuration or gh login. Use for shared-computer commits or an explicitly requested commit identity; ordinary commits use git-commit.
---

# Commit as a GitHub User

## Team agreement

Team members share computers. The computer owner's Git configuration and authenticated GitHub CLI account may differ from the person responsible for the code. For this workflow, **both the author and the committer of every new commit must identify the person requested by GitHub login**. This keeps code attribution traceable after work moves between computers.

The team accepts PRs created by the machine's existing `gh` account. GitHub's PR author is the authenticated actor; commit environment variables cannot change it. Do not switch accounts or rewrite commit identities to make them match the PR author. Commit metadata records attribution, not proof of authentication or legal ownership.

Use `gh`, Git, and Python 3.9 or newer. The helper uses only the Python standard library. Resolve supporting paths relative to this skill directory.

## Resolve the requested identity

1. Take the GitHub login from the user's request or an explicit identity already established for this work. Do not infer it from the machine owner, repository owner, `gh api user`, or local `user.name`. If it is missing, inspect and plan the commit groups first, then ask for the intended login before committing.
2. Run the read-only resolver:

   ```bash
   python3 "${CLAUDE_SKILL_DIR}/scripts/github_identity.py" resolve GITHUB_LOGIN
   ```

3. Show the resolved login, exact public profile name, commit email, and their sources. Read [references/identity-and-attribution.md](references/identity-and-attribution.md) when a field is missing, an override is requested, or signing/PR attribution needs explanation.

The public profile name is not a verified legal name. A private email cannot be retrieved through another person's `gh` session. The helper uses a public email when available; otherwise, for accounts created after the documented GitHub cutoff, it derives the account's ID-based GitHub noreply address and labels it as such. Missing names and ambiguous legacy noreply addresses require the person's supplied values. Never invent a personal email or silently substitute the login for the person's name.

Use `--name 'Person-supplied name'` and `--email 'Person-supplied account email'` only for values supplied by the person or already established in the conversation. Use `--noreply` when the user prefers the GitHub noreply address. These options apply to both `resolve` and `commit`; an explicit email and `--noreply` are mutually exclusive.

## Plan minimal coherent commits

Follow the existing [git-commit skill](../git-commit/SKILL.md) for branch and message conventions, substituting this skill's helper for every `git commit` invocation. On `develop`, create the appropriate `<type>/<description>` feature branch before committing. Use `cicd/` for the `ci/cd` type. Stop on detached HEAD until an intended working branch is established.

Inspect `git status --short`, staged and unstaged diffs, and untracked files. Record any pre-existing staged work before staging. Group by the smallest independently understandable, reviewable, and reversible behavior change, not by file count. Keep a change's necessary implementation, schema/config, and tests together; split unrelated behavior even when it shares a file. Order dependent commits so each has a coherent state. Do not fragment a feature into commits that are unusable on their own merely to reduce their size.

Show a short list of proposed commits with purpose, paths/hunks, and subject. Follow [commit conventions](../git-commit/references/commit-conventions.md) and the [scope guide](../git-commit/references/scope-guide.md): English type and domain scope, concise Korean subject, no body or AI co-author trailer. An existing request to commit is sufficient authorization to execute this plan.

## Create and verify each commit

1. Stage only the intended files or hunks with explicit paths or an inspected patch. Preserve unrelated staged work; never blindly commit the whole index or reset someone else's changes. When an overlapping staged hunk cannot be separated safely, resolve that specific ambiguity before committing.
2. Review `git diff --cached --check` and the complete staged diff. Write the chosen one-line subject to a UTF-8 file in a dedicated temporary directory outside the repository. Track the exact paths created for this run.
3. Run the helper for **each** group, repeating any identity options used in resolution:

   ```bash
   python3 "${CLAUDE_SKILL_DIR}/scripts/github_identity.py" commit GITHUB_LOGIN \
     --message-file /absolute/path/to/commit-message.txt
   ```

   `--dry-run` resolves and previews the identity and staged paths without creating a commit. The real command passes `GIT_AUTHOR_NAME`, `GIT_AUTHOR_EMAIL`, `GIT_COMMITTER_NAME`, and `GIT_COMMITTER_EMAIL` only to its child Git process and verifies the resulting raw metadata. It neither stages files nor edits configuration. `--author` alone is insufficient because it does not set the committer.
4. If hooks, signing, or Git fail, inspect the result before retrying. Keep hooks and signing policy active. Do not change signing keys, bypass verification, amend previous commits, or rewrite history as an automatic fallback. If post-commit verification fails, the commit may already exist; report its SHA and resolve the mismatch before continuing.
5. After verification, delete each run-created temporary message file. Before finishing, apply the cleanup below and report each new SHA/subject and the verified Author and Committer, plus any remaining changes. Verify that the repository's Git configuration and the machine's authenticated account remain unchanged. Identity environment variables must never be exported into the parent shell, shell profile, global config, or repository config.

Create/push a PR only when requested, using the existing [write-pr skill](../write-pr/SKILL.md) and current `gh` account. Explain that its author can legitimately differ from the commits. This agreement does not authorize a PR or push merely because a commit was requested.

## Clean up temporary files

Before finishing the run, including dry runs, failed attempts, or cancellation, delete its temporary commit-message files, saved identity/API responses, patch files, and diagnostic files, then remove the run-specific directories once empty. First inspect any uncertain commit outcome and report useful recovery information in the conversation. Do not leave cached identity files or scratch artifacts behind by default.

Delete only tracked files created by this run. A user-provided `--message-file`, repository files, reusable skill resources, and unrelated files must remain untouched; a final file is retained only when the user explicitly requests it. The helper does not delete its inputs, so the invoking agent must perform and verify this cleanup.
