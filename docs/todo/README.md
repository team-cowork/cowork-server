# TODO

미완료 작업의 상세 문서와 점검 스냅샷을 연결한다. 구현된 범위는 현재 상태에 요약하고 남은 작업만 기록한다.

## 진행 중

2026-10-02의 `5b236511` 코드를 대조한 판정이다. 구현 완료는 남은 운영·배포 확인까지 완료됐다는 뜻이 아니다.
실제 운영 자료·외부 클라이언트·과거 Config Git 원본은 이 저장소만으로 확인할 수 없다.
이번 점검은 소스·migration·설정·배포 스크립트를 확인했으며 빌드·테스트·운영 변경은 수행하지 않았다.

### 주요 변경 미구현 (13)

- monitoring: [Gateway canonical API 계약 모니터링](./items/11-monitoring/gateway-canonical-api-monitoring.md)
- security: [Preference 리소스별 권한 검증](./items/15-security/preference-resource-authorization.md)
- performance: [Idempotency operation repository 반환 계약 최적화](./items/37-performance/idempotent-operation-repository-contract.md)
- deployment: [배포 Vault 인증 자동화](./items/42-deployment/vault-auth-automation.md)
- deployment: [실행 중 릴리스를 보존하는 디스크 정리](./items/44-deployment/release-retention.md)
- dependency: [Firebase 최신 SDK 적용을 위한 FCM 식별자 FID 전환](./items/45-dependency/firebase-fid-migration.md)
- deployment: [운영 서비스의 CD 배포 일원화](./items/52-deployment/prod-cd-unification.md)
- deployment: [CD health 대기와 SSH 명령 제한 분리](./items/53-deployment/cd-health-wait-ssh-timeout.md)
- deployment: [VM 재부팅 후 Vault unseal 복구 절차](./items/54-deployment/vault-reboot-recovery.md)
- configuration: [notification FCM 자격 증명 Vault 등록](./items/55-configuration/notification-fcm-credentials.md)
- performance: [user projection 재생 처리량 개선](./items/56-performance/user-projection-replay-throughput.md)
- monitoring: [서비스 장기 중단 감지와 알림](./items/57-monitoring/service-outage-alerting.md)
- deployment: [모듈별 MySQL·Redis 배치 전환 검토](./items/58-deployment/module-datastore-placement.md)

### 부분 구현·정책 결정 필요 (11)

- monitoring: [메트릭 수집 장애 분석과 임시 Health Dashboard 제거](./items/11-monitoring/metrics-collection-recovery-and-health-dashboard-removal.md)
- storage: [오브젝트 스토리지 공개 접근 계약](./items/13-storage/object-storage-public-access-contract.md)
- security: [FCM device token 단일 계정 소유권 보장](./items/17-security/fcm-token-single-owner.md)
- correctness: [로드맵 노드 삭제 시 assignment 무결성 보장](./items/19-correctness/roadmap-node-assignment-cleanup.md)
- performance: [외부 I/O와 DB transaction 경계 분리](./items/21-performance/external-io-transaction-boundary.md)
- correctness: [정렬 position 동시성 보장](./items/22-correctness/ordered-position-concurrency.md)
- correctness: [채팅 메시지 채널·프로젝트·부모 범위 무결성 보장](./items/26-correctness/chat-message-scope-integrity.md)
- reliability: [Socket.IO Redis adapter 준비 상태와 복구 보장](./items/29-reliability/socketio-redis-adapter-readiness.md)
- reliability: [채팅 알림 전달의 종단간 멱등성 보장](./items/30-reliability/notification-delivery-idempotency.md)
- performance: [채팅 projection 증분 재개와 재구축 모드 분리](./items/31-performance/projection-incremental-resume.md)
- monitoring: [서비스 로그 수집 경로와 필드 정규화](./items/43-monitoring/log-collection-contract.md)

### 코드 반영 완료·빌드 또는 운영 검증 대기 (6)

- security: [Config Server 접근 보호](./items/08-security/config-server-access-control.md)
- configuration: [외부 Config Git 제거 및 prod native 전환](./items/09-configuration/remove-external-config-git.md)
- reliability: [JVM Kafka outbox relay 정체와 장기 transaction 제거](./items/18-reliability/jvm-kafka-outbox-relay.md)
- performance: [Gateway JSON 응답 전체 버퍼링 제거](./items/20-performance/gateway-response-buffering.md)
- security: [멤버십 회수 시 WebSocket 구독 강제 해제](./items/25-security/websocket-membership-revocation.md)
- security: [기존 역할·채널 정책 운영 환경 전환 수행](./items/51-security/channel-role-policy-production-transition.md)

## 점검 스냅샷

- [20261002](./20261002_TODO.md) — 스냅샷 미등록 스토리지 TODO 연결과 운영 장애 복구·CD 배포 실패 점검
- [20260930](./20260930_TODO.md) — 기존 역할·채널 정책 운영 전환
- [20260916](./20260916_TODO.md) — FCM 식별자·SDK 전환
- [20260910](./20260910_TODO.md) — 분산 배포 인증·로그·디스크 관리
- [20260830](./20260830_TODO.md) — 역할 command operation 반환 계약
- [20260828](./20260828_TODO.md) — 서버 애플리케이션 코드
- [20260825](./20260825_TODO.md) — Gateway API 계약·메트릭 수집
- [20260723](./20260723_TODO.md) — Config Server 운영 구성

## 작성 규칙

새 항목의 형식·번호·스냅샷 등록은 [todo-docs 스킬](../../.agents/skills/todo-docs/SKILL.md)을 따른다.
