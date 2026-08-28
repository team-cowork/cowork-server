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
- security: [Preference 리소스별 권한 검증](./items/15-security/preference-resource-authorization.md)
- correctness: [사용자 통합 검색의 MySQL 호환성 복구](./items/16-correctness/user-search-mysql-like.md)
- security: [FCM device token 단일 계정 소유권 보장](./items/17-security/fcm-token-single-owner.md)
- reliability: [JVM Kafka outbox relay 정체와 장기 transaction 제거](./items/18-reliability/jvm-kafka-outbox-relay.md)
- correctness: [로드맵 노드 삭제 시 assignment 무결성 보장](./items/19-correctness/roadmap-node-assignment-cleanup.md)
- performance: [Gateway JSON 응답 전체 버퍼링 제거](./items/20-performance/gateway-response-buffering.md)
- performance: [외부 I/O와 DB transaction 경계 분리](./items/21-performance/external-io-transaction-boundary.md)
- correctness: [정렬 position 동시성 보장](./items/22-correctness/ordered-position-concurrency.md)
- reliability: [Preference Redis cache 실패 격리](./items/23-reliability/preference-cache-failure-isolation.md)
- security: [통합 검색의 비공개 채널 노출 차단](./items/24-security/private-channel-search-visibility.md)
- security: [멤버십 회수 시 WebSocket 구독 강제 해제](./items/25-security/websocket-membership-revocation.md)
- correctness: [채팅 메시지 채널·프로젝트·부모 범위 무결성 보장](./items/26-correctness/chat-message-scope-integrity.md)
- reliability: [chat.message poison record 격리](./items/27-reliability/chat-message-poison-quarantine.md)
- search: [MongoDB-Elasticsearch 메시지 색인 정합성 복구](./items/28-search/elasticsearch-index-reconciliation.md)
- reliability: [Socket.IO Redis adapter 준비 상태와 복구 보장](./items/29-reliability/socketio-redis-adapter-readiness.md)
- reliability: [채팅 알림 전달의 종단간 멱등성 보장](./items/30-reliability/notification-delivery-idempotency.md)
- performance: [채팅 projection 증분 재개와 재구축 모드 분리](./items/31-performance/projection-incremental-resume.md)
- correctness: [사용자 프로필 PATCH 부분 수정 의미 보장](./items/32-correctness/user-profile-patch-semantics.md)
- reliability: [FCM 개별 전송 실패의 선택적 재시도](./items/33-reliability/fcm-partial-failure-retry.md)
- reliability: [Authorization 웹훅 멱등 처리와 outbox 원자화](./items/34-reliability/authorization-webhook-atomicity.md)
- reliability: [종료 음성 세션의 Redis stale cache 차단](./items/35-reliability/voice-session-cache-staleness.md)

## 점검 스냅샷

- [20260828](./20260828_TODO.md) — 서버 의존성·애플리케이션 코드 점검
- [20260825](./20260825_TODO.md) — Gateway·Swagger 외부 API 계약 점검
- [20260723](./20260723_TODO.md) — Config Server 운영 구성 점검

## 새 TODO 작성 규칙

```text
docs/todo/items/{순번}-{범주}/{작업명}.md
```

예: `docs/todo/items/01-urgent/write-post.md`
