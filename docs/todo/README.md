# TODO

미완료 작업의 상세 문서와 점검 스냅샷을 연결한다. `develop`에 해당 작업의 구현이 병합되었으면 완료로 처리한다.
로컬 동기화·`main` 반영·배포·검증 대기만으로 구현 TODO를 유지하지 않는다.
운영 자료를 확인하지 못했다는 이유로 완료 항목에 후속 범위를 덧붙이지 않는다.
코드·스크립트·Git·PR 이력에서 확인할 수 있는 설명과 일반적인 점검 절차는 삭제할 때 다른 문서로 옮기지 않는다.
문서에는 구현에서 드러나지 않는 결정 배경·운영 제약 등 프로젝트 고유 정보만 남긴다.
정책 결정·자격 증명 폐기는 별도 요청이나 관측 기록으로 남은 대상·범위가 확인된 독립 작업만 유지한다.
운영 승인·적용 기록의 부재나 일반적인 전환·폐기 절차만으로 독립 작업으로 분류하지 않는다.
2026-10-13 항목은 직접 관측한 운영 장애와 사용자가 요청한 VM 이름·역할 조정을 기록한 독립 작업이다.
이미 병합된 구현을 미완료로 되돌리는 항목과 구분한다.

## 진행 중

2026-10-13 기준 기존 상세 문서 18건에 운영 후속 작업 7건을 추가해 **25건**이다.
기존 구현 항목의 대조 기준은 2026-10-12 원격 `develop`의 `5a534586`, `main`의 `f68758e2`다.
2026-10-08 점검 이후 병합된 PR 중 관련 PR(#472·#473·#474·#475)의 변경과 해당 항목의 현재 코드를 대조했고,
나머지 항목은 당시 코드와 대조해 변동이 없음을 확인했다. 이번 추가 항목은 운영 상태와 GSM SV 패널 관측을 기준으로 한다.
항목별 현재 상태·남은 범위·근거는 각 상세 문서에 반영한다.

### 구현·설계 보완 필요 (16)

- monitoring: [Gateway canonical API 계약 모니터링](./items/11-monitoring/gateway-canonical-api-monitoring.md) — Gateway를 경유하는 비파괴 API probe와 별도 알림
- monitoring: [메트릭 수집 장애 분석과 임시 Health Dashboard 제거](./items/11-monitoring/metrics-collection-recovery-and-health-dashboard-removal.md) — 실제 수집 복구 판정과 임시 화면·공개 matcher 제거
- storage: [오브젝트 스토리지 공개 접근 계약](./items/13-storage/object-storage-public-access-contract.md) — 객체별 읽기 권한·공개 ingress·서명 endpoint·기존 URL 정책
- security: [Preference 리소스별 권한 검증](./items/15-security/preference-resource-authorization.md) — 프로젝트·채널 멤버 projection 기반 인가와 notification의 채널 멤버십 검증
- security: [FCM device token 단일 계정 소유권 보장](./items/17-security/fcm-token-single-owner.md) — data-only 메시지와 클라이언트 표시 정책의 동시 전환
- correctness: [로드맵 노드 삭제 시 assignment 무결성 보장](./items/19-correctness/roadmap-node-assignment-cleanup.md) — GLOBAL 관리·과제 보존 정책과 실제 데이터 전환
- performance: [외부 I/O와 DB transaction 경계 분리](./items/21-performance/external-io-transaction-boundary.md) — project 외부 대기 분리와 OAuth 저장 시점 재인가
- correctness: [정렬 position 동시성 보장](./items/22-correctness/ordered-position-concurrency.md) — 채널·프로젝트의 생성·reorder 공통 직렬화와 세 모듈의 기존 중복 정리·유일성 제약
- reliability: [채팅 알림 전달의 종단간 멱등성 보장](./items/30-reliability/notification-delivery-idempotency.md) — 전체 fan-out·unread 멱등성과 수신 측 eventId 계약
- performance: [채팅 projection 증분 재개와 재구축 모드 분리](./items/31-performance/projection-incremental-resume.md) — broker identity 확보와 부분 데이터 유실·source 교체의 복구 기준
- deployment: [배포 Vault 인증 자동화](./items/42-deployment/vault-auth-automation.md) — Actions용 단기 인증 또는 만료·교체 자동화
- monitoring: [서비스 로그 수집 경로와 필드 정규화](./items/43-monitoring/log-collection-contract.md) — 서비스별 필드·timestamp 정규화와 전 서비스 로그 도착 확인
- deployment: [실행 중 릴리스를 보존하는 디스크 정리](./items/44-deployment/release-retention.md) — 참조 기반 보존 정책·미리보기·적용 명령
- performance: [user projection 재생 처리량 개선](./items/56-performance/user-projection-replay-throughput.md) — DB 왕복·처리량 측정과 필요 쿼리·배치 개선
- monitoring: [서비스 장기 중단 감지와 알림](./items/57-monitoring/service-outage-alerting.md) — 기대 서비스 집합과 Vault seal·디스크 부족 알림
- configuration: [JS·TS 모듈 XO 기본 설정 전면 적용](./items/59-configuration/xo-zero-config.md) — 기본 규칙·기본 탐색 범위로 코드·빌드 구조 정리

### 별도 자격 증명 폐기 작업 (1)

- cleanup: [Config Server 기존 Vault 갱신 cron과 토큰 폐기](./items/61-cleanup/config-vault-legacy-token-retirement.md) — 운영 전환·7일 조건 충족 후 기존 cron·토큰·참조 폐기

### 운영 배치 결정 필요 (1)

- deployment: [모듈별 MySQL·Redis 배치 전환 검토](./items/58-deployment/module-datastore-placement.md) — 현재 VM·DB·Redis 자원 조사와 배치·예외 합의

### 운영 장애 복구와 VM 배치 정리 (7)

- deployment: [운영 배포 기준 SHA 복구](./items/62-deployment/runtime-deployment-baseline-recovery.md) — 실제 적용과 target별 runtime 성공 기준 복구
- deployment: [Production 배포 결과 표시 수정](./items/63-deployment/production-deployment-status.md) — 알림 성공과 운영 적용 성공의 표시 분리
- reliability: [User 배포 readiness 실패 원인 확인](./items/64-reliability/user-deployment-readiness-failure.md) — 후보 기동 자료 확보와 원본 projection 미준비 원인 확인
- security: [운영 Config 접근 보호와 AppRole 전환](./items/65-security/config-control-plane-rollout.md) — 서비스별 인증·AppRole 적용과 방화벽 실패 복구
- deployment: [운영 Eureka 등록과 광고 주소 복구](./items/66-deployment/eureka-registration-recovery.md) — 등록 누락·중복 Gateway·도달 불가 주소 정리
- configuration: [공개 API 진입점과 TLS 인증서 정합성 복구](./items/67-configuration/public-api-tls.md) — 공개 주소·전달 대상·신뢰 체인 정합성 확보
- deployment: [VM 이름과 서비스 배치 정리](./items/68-deployment/vm-placement-rebuild.md) — 이름 오타·역할 불일치 조정과 재생성 후 Vault·GitHub 배포 값 갱신

## 점검 스냅샷

- [20261013](./20261013_TODO.md) — 운영 배포 복구와 VM 이름·서비스 배치 점검
- [20261007](./20261007_TODO.md) — Config Server Vault 인증 전환 후속 운영 점검
- [20261005](./20261005_TODO.md) — XO 기본 설정 전면 적용 후속 작업
- [20261002](./20261002_TODO.md) — 스냅샷 미등록 스토리지 TODO 연결과 운영 장애 복구·CD 배포 실패 점검
- [20260910](./20260910_TODO.md) — 분산 배포 인증·로그·디스크 관리
- [20260828](./20260828_TODO.md) — 서버 애플리케이션 코드
- [20260825](./20260825_TODO.md) — Gateway API 계약·메트릭 수집

## 작성 규칙

새 항목의 형식·번호·스냅샷 등록은 [todo-docs 스킬](../../.agents/skills/todo-docs/SKILL.md)을 따른다.
