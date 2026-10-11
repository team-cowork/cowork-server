# Release Boundaries and Editorial Decisions

## Repository-specific behavior

Production release PRs usually merge `develop` into `main` and use a version title. Confirm this against the live PR and workflow on each run. Resolve tags through the commit API rather than treating a release's `target_commitish` as an immutable SHA; older releases can store a branch name there.

An observed example explains why release PRs and published releases must be distinguished: `v20260903.0` was created for PR #326, while the immediately preceding release PR was #313 (`v20260828.0`). The generated notes compared against the older published release `v20260818.0`. For a request for changes since the previous release PR, the boundary was therefore #313 to #326. These numbers are historical examples, not defaults for future runs.

Find the nearest preceding release PR by production-branch ancestry and confirm it is merged. Timestamp and title searches only discover candidates. Feature PRs can merge into `develop` or directly into `main`; collect both when their changes reach the target tag. PR numbering is repository-wide, so open, unmerged, unrelated, or later PRs inside a numeric interval are not automatically included.

The collector requires a previous release PR and optionally verifies the current one against the exact tag SHA. If this is the first release, use the documented initial release boundary and manually inspect the full tagged history. If the user explicitly chooses a tag boundary instead, resolve that tag and gather its comparison separately; do not pass a different PR just to satisfy the collector. The update helper expects a snapshot produced for the actual chosen boundary, so adapt the collection workflow before using it for a different mode.

## Coverage checks

- GitHub's comparison endpoint is limited without pagination; the helper paginates and checks `total_commits`. Its PR listing also paginates, so it has no fixed `gh pr list --limit` cutoff.
- Matching merge SHAs works for PRs whose merge commits survive in the tagged ancestry. Squash-merging an enclosing release, rebasing, or cherry-picking can erase those associations. Review the release PR's referenced PRs and full diff; compare candidate PR changes with the tagged code. Document any unresolved coverage instead of calling the inventory exhaustive.
- Review the listed commits too: direct pushes may have no PR. Keep their commit evidence in the working notes and review conversation. A verified consumer outcome still qualifies for a plain release bullet without a PR reference; do not invent one or append a commit-link section. The comparison API's file list is capped; it is not a substitute for a full Git diff or per-file inspection when coverage matters.
- Exclude net-reverted behavior. Describe the final supported behavior and avoid repeating it for each follow-up fix. Release merge PRs are containers, not user-facing features.

## Consumer-outcome examples

The facts in the middle column are required evidence, not benefits to assume from the implementation. Draft the changed consumer task or experience only after confirming those facts.

| Implementation evidence                                                                          | Consumer evidence                                                                                              | Editorial decision                                                                                                                                                                                                                  |
|--------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Processing now uses batches of 1–5000 records.                                                   | Only the worker's internal scheduling changed; the consumer contract and observable behavior are unchanged.    | Omit the item.                                                                                                                                                                                                                      |
| A batch endpoint accepts 1–5000 records.                                                         | API consumers previously had to submit records individually and can now submit several in one request.         | Describe the newly available bulk action: `여러 항목을 개별 요청 없이 한 번에 등록할 수 있도록 지원하였습니다.` Include the 5000-item limit only if it is the verified public request limit and helps consumers use the capability. |
| A batch implementation changed.                                                                  | Existing bulk requests previously failed for a documented input case and the tagged fix handles that case.     | Describe the previously failing task and its correction. Do not announce bulk processing as a new capability or claim it is faster without evidence.                                                                                |
| PRs add ownership checks to account settings and account-specific channel notification settings. | Consumers can no longer read or modify another account's settings; both PRs establish the same ownership rule. | Combine the outcome, cite both PRs, and describe the access restriction. Include any changed administrator behavior if it affects consumers.                                                                                        |
| A query gains an index or loses `DISTINCT`.                                                      | No consumer-visible failure, capability, contract change, or measured improvement is established.              | Omit the item. If evidence instead shows a specific consumer failure was fixed, describe that failure and the fix without promising a broader performance improvement.                                                              |
| Logging, deployment notifications, packages, or unused dependencies change.                      | Only maintainers' diagnostics or implementation organization change.                                           | Omit from product/API consumer notes. Reassess against the relevant audience if the user explicitly requests operator or maintainer notes.                                                                                          |

Preserve an externally meaningful limit, error response, permission change, or required action when it explains the consumer's new behavior. Avoid exposing batch sizes, lock types, database columns, or internal method names just because they appear in a PR. A real consumer problem can be fixed internally without a new UI or endpoint; evidence of that before/after behavior is what qualifies it.

## Writing decisions

The user's accepted historical notes, including `v20260818.0` and `v20260727.0`, are the format reference. Both contain only the heading `## 수정내역`, a blank line, and a flat list of short Korean sentences beginning with a bare PR number. Read the actual historical bodies before drafting; do not treat a rejected rewrite of the current release as the new convention.

Use `- #PR_NUMBER 에서 …하였습니다.` for an outcome supported by one PR, or `- #PR_A, #PR_B 에서 …하였습니다.` when related PRs support the same outcome. Cite the PRs needed to substantiate the sentence; attribution must not force separate bullets for one change. Keep each item to one concise sentence explaining what consumers can do or experience differently. Use inline code for a consumer-facing identifier when it helps. Do not replace bare PR references with Markdown links or add bold feature labels.

Prioritize evidenced capabilities, corrected consumer problems, and compatibility changes. Do not preserve an item merely because it is labeled a feature or bug fix. Select and group outcomes before polishing the historical sentence format. Express an essential change in defaults or compatibility in the affected item's sentence. Keep migration procedures, detailed caveats, and operational follow-up in the review conversation rather than creating extra release sections.

Do not add a summary introduction, category headings, an action checklist, a caution section, a comparison range, or a Full Changelog footer. The richer generated/current body is evidence to review, not a layout to preserve. Do not infer deployment success from a merged PR or a release object, and do not expose private infrastructure details from PR discussions.

## Editorial check before publishing

- Read each sentence without its PR references: can the intended consumer tell which task, problem, behavior, or required action changed? Rewrite implementation summaries; omit items with no demonstrated consumer effect.
- Check the before/after evidence against the final tag. Do not present an existing capability as new, an intention as an achieved benefit, or a future cleanup as completed.
- Check that related PRs describing one outcome have been combined and that separate outcomes are not collapsed merely because they share a PR. Do not fill a quota with internal work when few or no consumer outcomes remain.
- Keep only numbers and technical terms that help consumers understand or use the change. Verify limits and compatibility details instead of copying internal tuning values.
- Check meaningful consumer changes for omissions, including direct commits. Remove unsupported speed, availability, and security claims, then check the historical formatting and attribution.

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
