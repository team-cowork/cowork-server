# Commit & PR Conventions

## Commit Message Format

`type(scope): description`

- **Types**: `add` / `update` / `fix` / `refactor` / `ci/cd` / `docs` / `test` / `merge` (English)
- **Scope**: one value from `allowedScopes` in the CI workflow
- **Description**: Korean, no period, avoid endings: `~한다/~된다`, `~하기/~하기 위해`, `~합니다/~됩니다`, `~했습니다`
  - Good examples: `엔티티 필드 추가`, `트랜잭션 롤백 방지`, `로직 개선`
- Subject line only (no body)

## Scope Rules

Commit scopes and PR title scopes use the same values. Read `allowedScopes` in `.github/workflows/cowork-stage-ci.yml` for PRs to `develop`, or in `.github/workflows/cowork-prod-ci.yml` for PRs to `main`. Do not shorten a value or use another name for it. Do not use commit history to choose a scope, because CI validates PR titles only.

Map a changed `cowork-<name>/` directory to `<name>`:

```
// CORRECT
fix(authorization): OAuth 토큰 발급 로직 수정
update(user): 학생 조회 필터 추가

// WRONG — not in allowedScopes
fix(auth): 버그 수정
update(common): 엔티티 수정
```

Use these scopes for changes that are not in one module:

```
refactor(global): 공통 예외 처리 구조 개선
update(ci/cd): GitHub Actions 워크플로우 수정
```
