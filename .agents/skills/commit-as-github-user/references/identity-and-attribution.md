# Identity Sources and Attribution

## Values the helper can resolve

`gh api --hostname github.com users/LOGIN` returns the canonical login, numeric account ID, public display name, public email when configured, and account creation date. The helper accepts personal user accounts and rejects organizations and bots for this team workflow. API/network errors stop resolution instead of falling back to the machine identity.

The `name` field is the exact self-declared profile display name, not a legal-name verification service. If absent, obtain the person's preferred commit name through `--name`. Never infer a full name from a username, commit history, or the current CLI account.

The `email` field can be null. The `/user/emails` endpoint belongs to the authenticated account, so it cannot discover a teammate's private email from the machine owner's session. This helper deliberately does not call that endpoint or request authentication changes.

Email selection is deterministic:

1. A person-supplied `--email` takes precedence and is marked `user-supplied`; its ownership cannot be independently verified through the public profile API. The person must supply an address attached to their intended GitHub account.
2. Otherwise use the public profile email, unless `--noreply` was requested. The public API does not expose a `verified` flag for this field; do not invent one.
3. For accounts created after July 18, 2017, use `ID+LOGIN@users.noreply.github.com` when a public email is absent or `--noreply` is requested. This is a derived GitHub commit address, not a recovered private mailbox.
4. For accounts created on or before that date, the noreply format depends on historical privacy settings. Ask the person for the exact address from GitHub Settings → Emails and pass it as `--email`. Do not assume every legacy account has the modern format.

The resolver returns JSON with the canonical login, numeric ID, selected name/email, source labels, public profile fields, and profile URL. Do not use `eval` or shell-source this output. The commit subcommand passes values as a subprocess environment and argument array, so spaces, quotes, Unicode, dollar signs, and backticks are not interpreted by a shell. Control characters and Git identity delimiters are rejected.

## Git identities and GitHub actors

| Field | Meaning in this team workflow | How it is selected |
| --- | --- | --- |
| Commit Author | Person responsible for the code | Requested GitHub identity |
| Commit Committer | Person recorded as creating the commit | Same requested identity, by team agreement |
| PR author / pushing actor | Account authenticated to GitHub for that request | Machine's existing `gh` / transport credentials |
| Signature identity | Holder of the signing key | Existing signing configuration; separate from names/emails |

Git's four author/committer environment variables override configured names and emails for a single process. No `git config` write is needed, including local-only changes or temporary edits followed by restoration. The helper also passes the complete `--author` value and verifies `%an`, `%ae`, `%cn`, and `%ce` (raw fields rather than mailmap aliases) after creation.

Do not claim that a signature proves the requested teammate authenticated the commit. Leave signing policy in place; if its key or identity requirements prevent the commit, explain the actual failure. GitHub-created merge or squash commits can have different metadata from the branch commits; do not promise to control their committer through local environment variables. Changing merge strategy or rewriting published history is a separate task.

For a shared-machine verification, record only relevant configuration/account state: the repository config file checksum and `gh api --hostname github.com user --jq .login` are sufficient starting points. Do not print tokens, credential helpers' secrets, or a complete environment dump. The identity helper itself never requests the authenticated user's profile.

## Helper validation

Run `python3 -B -m unittest discover -s scripts -p 'test_*.py' -v` from this skill directory. [The test suite](../scripts/test_github_identity.py) mocks profile lookups and creates real commits only in disposable repositories. It checks missing/private fields, both identities across multiple commits, literal handling of shell-like names, unchanged configuration/environment, dry runs, and hook failures.

## Official sources

- [GitHub users API](https://docs.github.com/en/rest/users/users): public profile identity fields.
- [GitHub email API](https://docs.github.com/en/rest/users/emails#list-email-addresses-for-the-authenticated-user): authenticated-user email scope.
- [GitHub email reference](https://docs.github.com/en/account-and-profile/reference/email-addresses-reference): noreply formats and the account creation cutoff.
- [Git environment variables](https://git-scm.com/docs/git#_git_commits): per-process author and committer overrides.
- [GitHub CLI PR create](https://cli.github.com/manual/gh_pr_create): PR creation using the authenticated CLI session.
- [Agent Skills specification](https://agentskills.io/specification): portable `SKILL.md`, `scripts/`, and `references/` structure.
