# GitHub Labels Reference

Select **exactly 1 label** from the issue-eligible list below. Even when several labels apply, pick only the single highest-priority one. Do NOT use PR-only or manual labels.

## Issue-Eligible Labels (auto-selectable)

| Label                  | When to use                                               |
|------------------------|-----------------------------------------------------------|
| `bug:버그`             | Something does not work (issues written with `Bug.md`)    |
| `documentation:문서화` | Docs-only work (README, CONTRIBUTING, comments)           |
| `enhancement:개선작업` | New feature, improvement to existing feature, refactoring |

## Off-limits Labels (do NOT assign)

| Label                          | Reason                                                              |
|--------------------------------|---------------------------------------------------------------------|
| `release:릴리즈`               | PRs only                                                            |
| `harness sync:하네스 동기화`   | PRs only                                                            |
| `waiting for review:검토 대기` | PRs only, applied manually when ready for review                    |
| `blocked:차단됨`               | Applied manually when blocked by another PR/issue                   |
| `duplicate:중복`               | Applied during triage after the issue exists                        |
| `invalid:무효한`               | Applied during triage after the issue exists                        |
| `help wanted:도움 필요`        | Maintainer judgement; add only when the user asks                   |
| `GFI:첫 기여 추천`             | Maintainer judgement; add only when the user asks                   |

## Quick Decision

Walk the list top-down and stop at the first match — that is the one label to apply.

```
Something does not work?     → bug:버그
Docs only?                   → documentation:문서화
New feature or improvement?  → enhancement:개선작업
Unsure?                      → enhancement:개선작업
```

## Label Not Present in the Repository

These labels are a convention, not a guarantee — a target repository may not have them.
If the chosen label does not exist there, create the issue **without any label** instead of
creating the label or substituting another one. `scripts/create-issue.sh` handles this
automatically: it verifies the label up front and drops it when missing.
