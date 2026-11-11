# Commit Scope Selection Guide

## Source of Truth

Commit scopes and PR title scopes use the same values. The allowed values are in `allowedScopes` in the CI workflow:

- PRs to `develop`: `.github/workflows/cowork-stage-ci.yml`
- PRs to `main`: `.github/workflows/cowork-prod-ci.yml`

Read this list before you choose a scope. Do not copy the list into a document. Do not shorten a value or use another name for it.

CI validates PR titles only, so past commit subjects can contain scopes that are not allowed. Do not use commit history to choose a scope.

## Select the Scope

1. Run `git diff --name-only`.
2. Map each changed `cowork-<name>/` directory to `<name>`. For example, `cowork-authorization/` maps to `authorization`.
3. If the change is in one module, use that module's value.
4. If the change is in several modules or applies to the whole repository, use `global`. Agent skills and shared documents also use `global`.
5. If the change is to build or deployment pipelines, use `ci/cd`. If the change is to monitoring, use `monitoring`.

Use one scope for each commit and PR title.

## Examples

| Wrong | Correct | Reason |
|-------|---------|--------|
| `fix(auth): 로그인 버그 수정` | `fix(authorization): 로그인 버그 수정` | `auth` is not in `allowedScopes` |
| `[auth] 토큰 검증 수정` | `[authorization] 토큰 검증 수정` | `auth` is not in `allowedScopes` |
| `update(common): 유저 엔티티 수정` | `update(user): 엔티티 필드 추가` | `cowork-user/` maps to `user` |
| `fix(deploy): 배포 대기 시간 수정` | `fix(ci/cd): 배포 대기 시간 수정` | `deploy` is not in `allowedScopes` |

## Correct Cross-cutting Usage

```
refactor(global): 공통 예외 처리 로직 개선
update(ci/cd): GitHub Actions 워크플로우 최적화
```
