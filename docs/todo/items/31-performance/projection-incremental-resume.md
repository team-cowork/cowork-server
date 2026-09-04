# 채팅 projection 증분 재개와 재구축 모드 분리

- **서비스**: cowork-chat
- **우선순위**: 🟠 중간
- **현재 상태**: dataset 세대 기반 증분 재개·명시적 rebuild·상태 조회·지표가 구현되어 있으나, topic UUID 없는 client의 재시작 정책과 공통 규칙의 정합성 및 실제 다중 replica 복구 검증이 남아 있음

## 진행 상태 (2026-09-03)

| 항목 | 코드에서 확인한 상태 | 남은 확인 |
|------|---------------------|-----------|
| dataset identity | `ProjectionDatasetRepository`가 stream·source generation·dataset generation·상태를 저장함 | 부분 데이터 손상과 동일 이름 topic 교체를 탐지할 수 있는 범위를 검증함 |
| 정상 assignment | `claimForAssignment`가 기존 `nextOffset`·snapshot barrier를 보존하고 lease를 교체함 | process restart와 동일 process rebalance를 안전 정책에 맞게 구분함 |
| startup 검사 | `resolveStartupDataset`이 generation, retained 범위, partition 집합, barrier·invalid latch를 검사함 | broker topic UUID 대신 운영 env generation을 신뢰하는 한계를 해소함 |
| 명시적 rebuild | `ProjectionAdminController`와 readiness coordinator가 stream별 요청·pause·reset·replay를 수행함 | 실제 여러 replica에서 lease·pause 경합과 중단 후 복구를 검증함 |
| 관측 | 관리 상태 응답과 `ProjectionMetricsService`의 replay·catch-up·rebuild 지표가 존재함 | 배포 환경에서 endpoint와 지표를 확인함 |
| 검증 | 기존 checkpoint·readiness·rebuild 기술 테스트는 저장소 테스트 원칙에 따라 제거함 | 상태 전이 정적 검토, 운영 지표, 복구 rehearsal로 확인함 |

## 문제

최초 점검 당시에는 shared checkpoint가 있어도 assignment마다 retained low부터 다시 소비해 정상 재시작·rebalance의 MongoDB 쓰기와 비가용 시간이 커졌다. 현재 `cowork-chat/src/common/kafka/projection-readiness.service.ts`에는 9개 projection stream이 등록되어 있고, 정상 claim은 저장된 checkpoint부터 `consumer.seek`한다. 따라서 모든 assignment가 무조건 전체 log를 재생한다는 이전 설명은 현재 구현에 해당하지 않는다.

현재 구현은 `CHAT_PROJECTION_SOURCE_GENERATION` 또는 stream별 override와 MongoDB dataset generation을 비교한다. 이 값은 운영자가 관리하는 세대 값이며 broker topic UUID 자체가 아니다. source generation과 offset 범위가 우연히 같은 동일 이름 topic 교체까지 연속성을 증명하지는 못한다.

[Kafka 공통 규칙](../../../../.claude/rules/kafka-projections.md)은 topic identity를 얻지 못하는 client가 process restart 또는 forced recovery를 넘을 때 durable replay generation, checkpoint·barrier reset, stale lease fencing과 fresh replay를 수행하도록 요구한다. 이 복구 계약은 구현·운영 검증 사항이며 핵심 단위 테스트의 예외를 만들지 않는다. 현재 재시작 시 증분 경로는 이 규칙과 차이가 있으므로 성능 구현만으로 이 항목을 완료 처리하지 않는다. topic UUID를 검증할 수 있는 경계를 확보하거나 restart·forced recovery를 공통 replay 정책에 맞추는 후속 구현이 필요하다.

## 현재 구현의 실행 모드

| 모드 | 진입 조건 | 시작 offset | readiness 조건 |
|------|-----------|-------------|----------------|
| 증분 재개 | `ACTIVE` dataset·source 세대와 partition 집합이 일치하고 checkpoint가 retained 범위 안에 있음 | 저장된 `nextOffset` | 유효한 snapshot barrier·invalid latch 부재를 확인하고 현재 high-watermark까지 catch-up함 |
| 최초 bootstrap | projection dataset과 checkpoint가 모두 없음 | retained low | 유효한 전체 snapshot barrier와 high-watermark를 확인함 |
| 명시적 재구축 | 운영자가 dataset 초기화·topic 세대 교체·스키마 재생성을 요청함 | 검증된 rebuild 기준점 | 새 dataset의 전체 snapshot과 catch-up을 확인함 |
| 복구 불가 | checkpoint가 retention 밖이거나 dataset·checkpoint 세대가 다름 | 자동 seek하지 않음 | fail closed 후 명시적 재구축을 요구함 |

이 표는 코드의 모드 분류이며 non-UUID 재시작 증분 경로에 대한 정책 승인을 뜻하지 않는다. `cowork-chat/src/main.ts`는 projection 준비 전 일반 HTTP 요청을 `503`으로 차단하고 WebSocket 연결과 Eureka 등록을 보류한다. 시작 시점뿐 아니라 현재 broker high-watermark와 checkpoint도 계속 대조한다.

## 할 일

### topic identity와 재시작 경계

- broker topic UUID를 checkpoint와 함께 검증하는 경계를 확보하거나, UUID를 얻지 못하는 동안 process restart·forced recovery마다 공통 규칙의 durable replay generation을 생성하도록 한다.
- 같은 process의 정상 rebalance와 process restart를 구분하고, 증분 재개 허용 조건을 코드와 운영 문서에 동일하게 반영한다.
- generation 및 `[low, high]` 범위 검사만으로 동일 이름 topic의 연속성을 보장한다고 가정하지 않는다.
- 빈 collection·checkpoint 누락·세대 불일치 검사와 active lease CAS가 실제 MongoDB에서 보장하는 범위를 운영 절차로 확인한다.

### 명시적 재구축

- 관리 경로의 ADMIN 인가는 guard 단위 테스트로 검증하고 Gateway route 연결은 정적으로 점검한다.
- rebuild의 pause·reset·replay와 process 재시작 결과는 상태 지표와 운영 rehearsal로 확인한다.
- `channelMember` 재구축 시 채팅 소유 필드 보존 조건을 update query와 데이터 점검으로 확인한다.
- 모든 partition의 fresh snapshot과 high-watermark 이후 dataset이 활성화되는지는 운영 환경에서 지표로 확인한다.
- 일부 stream 재구축 동안 전체 API와 다른 stream의 가용성 범위를 운영 문서에 고정한다.

### readiness와 관측

- 이미 구현된 stream별 상세 상태와 replay·catch-up·rebuild 지표가 모니터링에서 수집되는지 확인한다.
- 정상 재시작, retention gap, MongoDB 초기화, topic 세대 교체별 운영 절차를 공통 recovery 정책과 일치시킨다.
- 증분 성능 개선은 안전 조건을 충족하는 경로에 한해 full replay와 비교 측정한다.

## 검증

- ADMIN 인가와 불완전한 상태의 접근 거부 같은 보안 판단만 guard 단위 테스트로 검증한다.
- topic identity, checkpoint·barrier 재사용, retention gap, dataset 세대 상태 전이를 코드와 저장 schema에서 정적으로 점검한다.
- rebalance, restart, forced recovery, 명시적 rebuild의 결과는 stream별 상태·replay·catch-up metric과 운영 rehearsal로 확인한다.
- 여러 replica와 partition의 lease fencing, offset 전진, readiness는 배포 환경 관측과 복구 runbook으로 검증한다.
- Kafka·MongoDB를 구동하거나 checkpoint, readiness, snapshot, replay, rebuild, fencing을 고정하는 자동화 단위·통합·회귀 테스트는 추가하지 않는다.

## 완료 조건

- 증분 재개 허용 경계가 공통 Kafka recovery 규칙과 일치하며, topic UUID 없는 process restart·forced recovery는 durable generation을 새로 만들고 fresh replay를 완료한다.
- 연속성이 입증된 rebalance·재개 경로만 유효한 shared checkpoint를 재사용한다.
- checkpoint와 MongoDB projection dataset의 세대 불일치가 탐지되어 잘못된 증분 재개를 허용하지 않는다.
- retention gap과 invalid 상태가 자동 merge replay로 숨겨지지 않고 재구축 필요 상태로 표시된다.
- readiness와 지표에서 stream별 증분 재개·bootstrap·재구축 상태를 구분할 수 있다.
- 증분 재개 이후에도 현재 assignment fencing과 snapshot 기반 복구 안전성이 유지되어 있다.
