# 채팅 projection 증분 재개와 재구축 모드 분리

- **서비스**: cowork-chat
- **우선순위**: 🟠 중간
- **현재 상태**: dataset 세대·checkpoint 증분 재개·명시적 rebuild는 구현되어 있으며 broker identity 확보와 운영 복구 확인이 남아 있다.

## 문제

현재 Chat은 운영자가 관리하는 `CHAT_PROJECTION_SOURCE_GENERATION`과 MongoDB dataset identity,
retained offset 범위로 재개를 판단한다. 같은 이름·세대·겹치는 offset의 broker topic 교체를
이 값만으로 탐지할 수는 없다.

collection 손상 검사는 활성화 당시 문서가 있던 collection이 완전히 비었는지를 확인한다.
문서 일부만 유실된 경우는 dataset 세대·offset 범위가 그대로일 수 있으므로 이 검사로 탐지하지 못한다.

운영 source 세대는 broker UUID와 동등한 증거가 아니다. 이 제약은
[Kafka 공통 규칙](../../../../.claude/rules/kafka-projections.md)에 명시되어 있다.
checkpoint·readiness·rebuild 구현 설명을 반복하지 않고 연속성 증거와 실제 복구 경계를 확인한다.

## 코드 근거

- [재개 판정](../../../../cowork-chat/src/common/kafka/projection-readiness.service.ts#L620): topic·group·운영 세대·Mongo dataset·offset 범위를 확인하지만 broker UUID는 비교하지 않는다.
- [재구축 reset](../../../../cowork-chat/src/common/kafka/projection-dataset.repository.ts#L217): channelMember는 삭제 표시와 source 상태만 초기화하여 채팅 소유 필드를 보존한다.

## 할 일

- broker topic UUID를 확보·저장·검증할 client 경계를 마련한다.
- 확보 전 source 교체·손상·연속성 불명은 fail closed와 명시적 dataset 재구축으로 처리한다.
- 부분 collection 유실의 발견·운영 재구축 기준과 source 교체 정보의 공급 경계를 정한다.
- 여러 replica의 pause·lease fencing·reset·replay와 중단 후 재개를 수동 확인한다.
- `channelMember` 재구축이 채팅 소유 필드를 보존하는지 실제 데이터로 대조한다.
- 재시작·retention gap·MongoDB 초기화·topic 교체별 복구 절차와 증분 성능을 기록한다.

## 검증

- 관리 경로의 `ADMIN` 인가·불완전 상태의 접근 거부만 핵심 보안 단위 테스트로 확인한다.
- topic identity·dataset·checkpoint·barrier 상태 전이는 schema·코드로 정적 점검한다.
- restart·rebalance·rebuild·replica fencing의 결과는 stream 상태·지표와 수동 운영 점검으로 확인한다.

## 완료 조건

- broker 연속성을 확인한 경우에만 checkpoint를 재사용한다.
- 교체·손상·retention gap은 이전 worker를 차단하고 projection·checkpoint·barrier를 함께 재구축한다.
- 채팅 소유 필드를 보존하며 실제 복구와 성능 결과를 확인할 수 있다.

## 점검 근거 (2026-10-08)

- 관련 PR: [#320](https://github.com/team-cowork/cowork-server/pull/320) · [#346](https://github.com/team-cowork/cowork-server/pull/346) · [#355](https://github.com/team-cowork/cowork-server/pull/355).
- [대조 코드](../../../../cowork-chat/src/common/kafka/projection-readiness.service.ts): sourceGeneration·dataset·offset 재개와 rebuild는 구현되었다. broker topic UUID를 비교하지 않고 활성 collection의 완전 소실만 검사한다.
- 판정: **부분 구현**. broker identity 확보와 부분 데이터 유실·source 교체의 복구 기준을 남은 범위로 유지한다.
