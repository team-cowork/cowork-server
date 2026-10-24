# Release Boundaries and Editorial Decisions

## Repository-specific behavior

Production release PRs usually merge `develop` into `main` and use a version title. Confirm this against the live PR and workflow on each run. Resolve tags through the commit API rather than treating a release's `target_commitish` as an immutable SHA; older releases can store a branch name there. Current release tags are annotated reservation tags on the CI-validated source commit, which is the release PR's merge commit on `main`, not the later module-version push commit; `commits/TAG` dereferences them to that commit, which is what the collector compares with the current release PR's merge SHA.

An observed example explains why release PRs and published releases must be distinguished: `v20260903.0` was created for PR #326, while the immediately preceding release PR was #313 (`v20260828.0`). The generated notes compared against the older published release `v20260818.0`. For a request for changes since the previous release PR, the boundary was therefore #313 to #326. These numbers are historical examples, not defaults for future runs.

Find the nearest preceding release PR by production-branch ancestry and confirm it is merged. Timestamp and title searches only discover candidates. Feature PRs can merge into `develop` or directly into `main`; collect both when their changes reach the target tag. PR numbering is repository-wide, so open, unmerged, unrelated, or later PRs inside a numeric interval are not automatically included.

The collector requires a previous release PR and optionally verifies the current one against the exact tag SHA. If this is the first release, use the documented initial release boundary and manually inspect the full tagged history. If the user explicitly chooses a tag boundary instead, resolve that tag and gather its comparison separately; do not pass a different PR just to satisfy the collector. The update helper expects a snapshot produced for the actual chosen boundary, so adapt the collection workflow before using it for a different mode.

## Coverage checks

- GitHub's comparison endpoint is limited without pagination; the helper paginates and checks `total_commits`. Its PR listing also paginates, so it has no fixed `gh pr list --limit` cutoff.
- Matching merge SHAs works for PRs whose merge commits survive in the tagged ancestry. Squash-merging an enclosing release, rebasing, or cherry-picking can erase those associations. Review the release PR's referenced PRs and full diff; compare candidate PR changes with the tagged code. Document any unresolved coverage instead of calling the inventory exhaustive.
- Review the listed commits too: direct pushes may have no PR. Keep their commit evidence in the working notes and review conversation. For a selected end-user or technical change without a PR, prefix the bullet with its verified full 40-character commit SHA from the selected comparison, followed by `에서`. Do not invent a PR number, omit the source, or append a separate commit-link section. The comparison API's file list is capped; it is not a substitute for a full Git diff or per-file inspection when coverage matters.
- Exclude net-reverted behavior. Describe the final supported behavior and avoid repeating it for each follow-up fix. Release merge PRs are containers, not user-facing features.

## End-user changes and technical fallback examples

End users are the people using the cowork app, not the client developers calling this server. Prioritize their experience. If the release has no user-facing changes, explain substantive technical work in language they can follow, without claiming it changed their app experience. These hypothetical examples are not facts about a particular release or sentences to copy; each depends on confirming the behavior in the middle column.

| Implementation evidence | Verified behavior | Editorial decision |
| --- | --- | --- |
| Search accepts a new date filter. | End users can now narrow results to a period they choose. | Describe the task: `필요한 기간의 기록을 골라 조회할 수 있도록 지원하였습니다.` |
| Partial-update handling is corrected. | Changing a display name previously erased the saved profile image; the fix preserves it. | Describe the resolved problem: `이름을 변경할 때 기존 프로필 사진이 삭제되던 문제를 수정하였습니다.` |
| Internal certificate trust configuration is corrected. | The server previously rejected the connection needed to load its settings. | When there are no user-facing changes: `서버가 실행에 필요한 설정을 불러올 때 발생하던 인증서 확인 오류를 수정하였습니다.` Do not infer an app outage. |
| Monitoring queries and panels are corrected. | Operators can distinguish response times and errors; a total was previously displayed as an average. | When there are no user-facing changes: `운영자가 서비스의 응답 시간과 오류를 확인하는 화면을 정리하고, 평균 응답 시간이 잘못 표시되던 문제를 수정하였습니다.` Do not claim faster responses or recovery. |
| Credentials are issued for each deployment instead of reused. | Server access credentials are replaced during deployment; app sign-in is unchanged. | When there are no user-facing changes: `서버가 운영 설정에 접근하는 인증 정보를 배포할 때마다 새로 발급하도록 변경하였습니다.` Do not imply changes to users' passwords. |
| Code is moved between files. | App and operational behavior are unchanged. | Omit this low-value detail even in a technical-only release; do not invent a benefit to fill the notes. |

Preserve a limit, permission change, or required action when end users meet it in the app. API identifiers, error codes, internal tuning values, and implementation names do not appear merely because they occur in a PR. An internal fix can still resolve a real end-user problem without adding a UI or endpoint; describe the evidenced before/after behavior without inventing broader benefits.

## Writing decisions

The user's accepted historical notes, including `v20260818.0` and `v20260727.0`, are the format reference. Both contain only the heading `## 수정내역`, a blank line, and a flat list of short Korean sentences beginning with a bare PR number. Read the actual historical bodies before drafting; do not treat a rejected rewrite of the current release as the new convention.

Use `- #PR_NUMBER 에서 …하였습니다.` for a change supported by one PR, or `- #PR_A, #PR_B 에서 …하였습니다.` when related PRs support the same change. For a change without a PR, use `- FULL_COMMIT_SHA 에서 …하였습니다.`. Cite the PRs or direct commits needed to substantiate the sentence, separating multiple sources with commas; attribution must not force separate bullets for one change. Keep each item to one concise sentence explaining the app experience or, in the technical fallback, the changed server or operational behavior. Avoid API paths, field names, and other identifiers that only client developers recognize. Keep source references as plain text without backticks or Markdown links, and do not add bold feature labels.

The user's accepted `v20261021.1` body demonstrates the direct-commit format. Use the actual supporting commit for each release, not this example's hash:

```markdown
## 수정내역

- c1ca672b891eec96eb4fc493e998b339432b51da 에서 서버가 실행에 필요한 설정을 불러올 때 발생하던 인증서 확인 오류를 수정하였습니다.
```

Prioritize evidenced capabilities and corrected end-user problems. If none exist, select substantive technical changes and explain what was changed or corrected, rather than publishing only a no-changes statement. Do not preserve an item merely because it is labeled a feature or bug fix. Select and group changes before polishing the historical sentence format. Express an essential change in defaults that end users notice in the affected item's sentence. Keep migration procedures, detailed caveats, and operational follow-up in the review conversation rather than creating extra release sections.

Do not add a summary introduction, category headings, an action checklist, a caution section, a comparison range, or a Full Changelog footer. The richer generated/current body is evidence to review, not a layout to preserve. Do not infer deployment success from a merged PR or a release object, and do not expose private infrastructure details from PR discussions.

## Editorial check before publishing

- Read each sentence without its source references: can an end user understand the changed app behavior or, when the release has no user-facing changes, the concrete technical change and its purpose? Translate jargon and name the server or operator when they are the affected actor.
- Check the before/after evidence against the final tag. Do not present an existing capability as new, an intention as an achieved benefit, or a future cleanup as completed.
- Check that related PRs describing one change have been combined and that separate changes are not collapsed merely because they share a PR. Do not pad a short user-facing release with internal work. If no user-facing changes remain, apply the technical fallback without inventing benefits or filling a bullet quota.
- Keep only numbers that describe what end users meet in the app. Verify limits instead of copying internal tuning values.
- Check meaningful changes for omissions, including direct commits and technical changes eligible for the fallback. Keep a technical purpose distinct from a measured user benefit. Remove unsupported speed, availability, and security claims, then check the historical formatting and attribution. For items without a PR, verify that each full commit SHA exists in the selected comparison and supports the stated change.

## Updating the existing release

Keep the original body in the temporary snapshot until the update and read-back checks finish, then remove the run-created snapshot, draft, and backup according to the cleanup instructions in `SKILL.md`. Do not retain local draft or backup files by default or return their paths after deletion; only user-requested file deliverables are preserved. The helper's returned backup path identifies a working file and does not imply permanent retention.

Only the body is edited; tag, title, assets, target, draft state, and prerelease state are outside this workflow. The helper checks for intervening changes immediately before writing and verifies afterward. GitHub CLI does not offer an atomic compare-and-swap for this edit, so do not describe the check as eliminating every concurrent-write race.

## Helper validation

For executable helper changes, when repository testing rules permit, run `python3 -B -m unittest discover -s scripts -p 'test_*.py' -v` from this skill directory. [The offline suite](../scripts/test_release_notes.py) checks pagination, ancestry filtering, stale-body and retag rejection, body-only writes, and uncertain-write read-back. GitHub writes are mocked. These checks do not evaluate the writing; use the editorial check above for content and instruction changes.

## Official sources

- [Agent Skills specification](https://agentskills.io/specification): `SKILL.md`, executable helpers in `scripts/`, guidance in `references/`, and templates in `assets/`.
- [Codex skill documentation](https://developers.openai.com/codex/skills/): repository discovery under `.agents/skills`.
- [Claude Code skills](https://code.claude.com/docs/en/skills): repository discovery under `.claude/skills` and supporting resources.
- [GitHub CLI release edit](https://cli.github.com/manual/gh_release_edit): update an existing body from `--notes-file`.
- [GitHub compare commits API](https://docs.github.com/en/rest/commits/commits#compare-two-commits): ancestry, pagination, and comparison limits.
