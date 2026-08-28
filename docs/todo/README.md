# 서버 TODO

현재 남아 있는 코드베이스 작업을 우선순위·범주별로 `items/` 아래 하위 디렉터리에서 관리한다. 완료한 항목은 취소선으로 표시하고 세부 문서는 그대로 둔다.

## 구성

- `items/{순번}-{범주}/{작업명}.md` — 개별 작업 세부 명세
- `{YYYYMMDD}_TODO.md` — 특정 시점 점검 세션의 요약 스냅샷 (아래 세부 문서로 링크)

## 진행 중

- security: [Config Server 접근 보호](./items/08-security/config-server-access-control.md)
- configuration: [외부 Config Git 제거 및 prod native 전환](./items/09-configuration/remove-external-config-git.md)
- monitoring: [메트릭 수집 장애 분석과 임시 Health Dashboard 제거](./items/11-monitoring/metrics-collection-recovery-and-health-dashboard-removal.md)
- monitoring: [Gateway canonical API 계약 모니터링](./items/11-monitoring/gateway-canonical-api-monitoring.md)
- storage: [오브젝트 스토리지 공개 접근 계약](./items/13-storage/object-storage-public-access-contract.md)
- dependency: [cowlib git override 제거와 Hex 릴리스 복귀](./items/14-dependency/cowlib-hex-override-removal.md)

## 점검 스냅샷

- [20260828](./20260828_TODO.md) — cowork-user 의존성 임시 override 점검
- [20260825](./20260825_TODO.md) — Gateway·Swagger 외부 API 계약 점검
- [20260723](./20260723_TODO.md) — Config Server 운영 구성 점검

## 새 TODO 작성 규칙

```text
docs/todo/items/{순번}-{범주}/{작업명}.md
```

예: `docs/todo/items/01-urgent/write-post.md`
