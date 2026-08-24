# 서버 TODO

현재 남아 있는 코드베이스 작업을 우선순위·범주별로 `items/` 아래 하위 디렉터리에서 관리한다. 완료한 세부 문서는 삭제하거나 완료 기록 문서로 옮기고, 이 목록에서도 제거한다.

## 구성

- `items/{순번}-{범주}/{작업명}.md` — 개별 작업 세부 명세
- `{YYYYMMDD}_TODO.md` — 특정 시점 점검 세션의 요약 스냅샷 (아래 세부 문서로 링크)

## 진행 중

- security: [Config Server 접근 보호](./items/08-security/config-server-access-control.md)
- configuration: [외부 Config Git 제거 및 prod native 전환](./items/09-configuration/remove-external-config-git.md)
- api: [외부 API 모듈 네임스페이스 통일](./items/10-api/public-route-namespace-migration.md)

## 점검 스냅샷

- [20260825](./20260825_TODO.md) — Gateway·Swagger 외부 API 계약 점검
- [20260723](./20260723_TODO.md) — Config Server 운영 구성 점검
- [20260526](./20260526_TODO.md) — 클라이언트 계약 기준 기능 점검 (전체 완료)

## 새 TODO 작성 규칙

```text
docs/todo/items/{순번}-{범주}/{작업명}.md
```

예: `docs/todo/items/01-urgent/write-post.md`
