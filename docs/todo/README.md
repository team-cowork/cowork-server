# TODO

미완료 작업의 상세 문서와 점검 스냅샷을 연결한다. `develop`에 해당 작업의 구현이 병합되었으면 완료로 처리한다.
로컬 동기화·`main` 반영·배포·검증 대기만으로 구현 TODO를 유지하지 않는다.
운영 자료를 확인하지 못했다는 이유로 완료 항목에 후속 범위를 덧붙이지 않는다.
코드·스크립트·Git·PR 이력에서 확인할 수 있는 설명과 일반적인 점검 절차는 삭제할 때 다른 문서로 옮기지 않는다.
문서에는 구현에서 드러나지 않는 결정 배경·운영 제약 등 프로젝트 고유 정보만 남긴다.
정책 결정·자격 증명 폐기 자체가 목적인 독립 작업은 그 범위를 명시해 유지한다.

## 진행 중

2026-10-08 기준 **20건**이다. 로컬 `58bddf1a`, 원격 `develop`의 `a2a0212a`, `main`의 `08706cdd`와
Git 이력·GitHub PR·실제 `cowork-runtime` 배포 기록을 대조했다. PR 목록 437건에서 관련 PR 42건의
변경·리뷰·검사 기록과 주요 11건의 인라인 리뷰를 확인했다. 항목별 현재 상태·남은 범위·근거는 각 상세 문서에 반영한다.

### 구현·설계 보완 필요 (18)

- monitoring: [Gateway canonical API 계약 모니터링](./items/11-monitoring/gateway-canonical-api-monitoring.md) — Gateway를 경유하는 비파괴 API probe와 별도 알림
- monitoring: [메트릭 수집 장애 분석과 임시 Health Dashboard 제거](./items/11-monitoring/metrics-collection-recovery-and-health-dashboard-removal.md) — 실제 수집 복구 판정과 임시 화면·공개 matcher 제거
- storage: [오브젝트 스토리지 공개 접근 계약](./items/13-storage/object-storage-public-access-contract.md) — 객체별 읽기 권한·공개 ingress·서명 endpoint·기존 URL 정책
- security: [Preference 리소스별 권한 검증](./items/15-security/preference-resource-authorization.md) — 프로젝트·채널 projection 인가와 notification의 계정 소유권 검증
- security: [FCM device token 단일 계정 소유권 보장](./items/17-security/fcm-token-single-owner.md) — data-only·클라이언트 표시 정책의 동시 전환과 migration 적용
- correctness: [로드맵 노드 삭제 시 assignment 무결성 보장](./items/19-correctness/roadmap-node-assignment-cleanup.md) — GLOBAL 관리·과제 보존 정책과 실제 데이터 전환
- performance: [외부 I/O와 DB transaction 경계 분리](./items/21-performance/external-io-transaction-boundary.md) — project 외부 대기 분리와 OAuth 저장 시점 재인가
- correctness: [정렬 position 동시성 보장](./items/22-correctness/ordered-position-concurrency.md) — 생성·reorder 공통 직렬화와 삭제 gap·기존 중복 처리
- reliability: [채팅 알림 전달의 종단간 멱등성 보장](./items/30-reliability/notification-delivery-idempotency.md) — 전체 fan-out·unread 멱등성과 수신 측 eventId 계약
- performance: [채팅 projection 증분 재개와 재구축 모드 분리](./items/31-performance/projection-incremental-resume.md) — broker identity 확보와 부분 데이터 유실·source 교체의 복구 기준
- performance: [Idempotency operation repository 반환 계약 최적화](./items/37-performance/idempotent-operation-repository-contract.md) — 신규·기존 구분 repository 결과 계약과 안전한 조회 생략
- deployment: [배포 Vault 인증 자동화](./items/42-deployment/vault-auth-automation.md) — Actions용 단기 인증 또는 만료·교체 자동화
- monitoring: [서비스 로그 수집 경로와 필드 정규화](./items/43-monitoring/log-collection-contract.md) — Chat prod 출력 수집과 서비스별 필드·timestamp 정규화
- deployment: [실행 중 릴리스를 보존하는 디스크 정리](./items/44-deployment/release-retention.md) — 참조 기반 보존 정책·미리보기·적용 명령
- dependency: [Firebase 최신 SDK 적용을 위한 FCM 식별자 FID 전환](./items/45-dependency/firebase-fid-migration.md) — 프로젝트가 정한 FID 전용 계약과 클라이언트·서버 동시 전환
- performance: [user projection 재생 처리량 개선](./items/56-performance/user-projection-replay-throughput.md) — DB 왕복·처리량 측정과 필요 쿼리·배치 개선
- monitoring: [서비스 장기 중단 감지와 알림](./items/57-monitoring/service-outage-alerting.md) — 기대 서비스 집합·인프라 알림과 상위 CI 결론 반영
- configuration: [JS·TS 모듈 XO 기본 설정 전면 적용](./items/59-configuration/xo-zero-config.md) — 기본 규칙·기본 탐색 범위로 코드·빌드 구조 정리

### 별도 자격 증명 폐기 작업 (1)

- cleanup: [Config Server 기존 Vault 갱신 cron과 토큰 폐기](./items/61-cleanup/config-vault-legacy-token-retirement.md) — 운영 전환·7일 조건 충족 후 기존 cron·토큰·참조 폐기

### 운영 배치 결정 필요 (1)

- deployment: [모듈별 MySQL·Redis 배치 전환 검토](./items/58-deployment/module-datastore-placement.md) — 현재 VM·DB·Redis 자원 조사와 배치·예외 합의

## 점검 스냅샷

- [20261007](./20261007_TODO.md) — Config Server Vault 인증 전환 후속 운영 점검
- [20261005](./20261005_TODO.md) — XO 기본 설정 전면 적용 후속 작업
- [20261002](./20261002_TODO.md) — 스냅샷 미등록 스토리지 TODO 연결과 운영 장애 복구·CD 배포 실패 점검
- [20260916](./20260916_TODO.md) — FCM 식별자·SDK 전환
- [20260910](./20260910_TODO.md) — 분산 배포 인증·로그·디스크 관리
- [20260830](./20260830_TODO.md) — 역할 command operation 반환 계약
- [20260828](./20260828_TODO.md) — 서버 애플리케이션 코드
- [20260825](./20260825_TODO.md) — Gateway API 계약·메트릭 수집

## 작성 규칙

새 항목의 형식·번호·스냅샷 등록은 [todo-docs 스킬](../../.agents/skills/todo-docs/SKILL.md)을 따른다.
