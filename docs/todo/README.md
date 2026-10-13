# TODO

미완료 작업의 상세 문서와 발견 당시의 점검 스냅샷을 연결한다. 상세 문서에는 남은 범위·결정 이유·완료 조건을 둔다.
완료한 항목과 참조는 삭제한다. 코드·설정·Git 이력의 복제본은 남기지 않는다.

## 진행 중

미완료 상세 문서는 **12건**이다.

### 구현·설계 보완 필요 (12)

- monitoring: [Gateway canonical API 계약 모니터링](./items/11-monitoring/gateway-canonical-api-monitoring.md) — Gateway를 경유하는 비파괴 API probe와 별도 알림
- security: [Preference 리소스별 권한 검증](./items/15-security/preference-resource-authorization.md) — 프로젝트·채널 멤버 projection 기반 인가와 notification의 채널 멤버십 검증
- security: [FCM device token 단일 계정 소유권 보장](./items/17-security/fcm-token-single-owner.md) — data-only 메시지와 클라이언트 표시 정책의 동시 전환
- performance: [외부 I/O와 DB transaction 경계 분리](./items/21-performance/external-io-transaction-boundary.md) — project 외부 대기 분리와 OAuth 저장 시점 재인가
- correctness: [정렬 position 동시성 보장](./items/22-correctness/ordered-position-concurrency.md) — 채널·프로젝트의 생성·reorder 공통 직렬화와 세 모듈의 기존 중복 정리·유일성 제약
- reliability: [채팅 알림 전달의 종단간 멱등성 보장](./items/30-reliability/notification-delivery-idempotency.md) — 전체 fan-out·unread 멱등성과 수신 측 eventId 계약
- performance: [채팅 projection 증분 재개와 재구축 모드 분리](./items/31-performance/projection-incremental-resume.md) — broker identity 확보와 부분 데이터 유실·source 교체의 복구 기준
- deployment: [배포 Vault 인증 자동화](./items/42-deployment/vault-auth-automation.md) — 앱 CD·설정 변경의 단기 인증과 복구 인증 수명 관리
- monitoring: [서비스 로그 시각과 수집 누락 식별](./items/43-monitoring/log-collection-contract.md) — local·prod 시각 정책과 파싱 실패·수집 누락 지표
- deployment: [실행 중 릴리스를 보존하는 디스크 정리](./items/44-deployment/release-retention.md) — 참조 기반 보존 정책·미리보기·적용 명령
- monitoring: [Vault 봉인과 호스트 디스크 부족 감지](./items/57-monitoring/service-outage-alerting.md) — Vault seal·디스크 수집과 알림
- configuration: [JS·TS 모듈 XO 기본 설정 전면 적용](./items/59-configuration/xo-zero-config.md) — 기본 규칙·기본 탐색 범위로 코드·빌드 구조 정리

## 점검 스냅샷

- [20261005](./20261005_TODO.md) — XO 기본 설정 전면 적용 후속 작업
- [20261002](./20261002_TODO.md) — 운영 장애 복구·CD 배포 실패 점검
- [20260910](./20260910_TODO.md) — 분산 배포 인증·로그·디스크 관리
- [20260828](./20260828_TODO.md) — 서버 애플리케이션 코드
- [20260825](./20260825_TODO.md) — Gateway API 계약·메트릭 수집

## 작성 규칙

새 항목의 형식·번호·스냅샷 등록은 [todo-docs 스킬](../../.agents/skills/todo-docs/SKILL.md)을 따른다.
