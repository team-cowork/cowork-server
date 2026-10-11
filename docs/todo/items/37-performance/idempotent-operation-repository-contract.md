# Idempotency operation repository 반환 계약 최적화

- **서비스**: cowork-channel, cowork-team, cowork-project
- **우선순위**: 🟡 낮음
- **현재 상태**: insert-or-no-op 뒤 canonical 행을 잠금 조회하며 신규 삽입에도 조회 한 번이 추가된다.

## 문제

현재 operation 접수는 중복·경합의 실제 승자 행을 읽어 요청 hash와 상태를 판정한다.
이 방식은 정확성을 유지하지만 신규 삽입에서도 후속 조회를 수행한다.

MySQL/JDBC affected-row 값만으로 신규와 duplicate no-op을 구분하면 driver 설정에 따라
패배한 요청을 신규로 오판할 수 있다. 조회 최적화는 이 의미를 캡슐화한 repository 계약이 필요하다.

## 코드 근거

- [Channel 접수](../../../../cowork-channel/src/main/kotlin/com/cowork/channel/domain/channelRolePolicy/operation/ChannelRolePolicyCommandSubmission.kt#L36): insert-or-no-op 뒤 항상 canonical 잠금 조회를 수행한다. 신규 삽입도 조회를 생략하지 않는다.
- [Team 접수](../../../../cowork-team/src/main/kotlin/com/cowork/team/domain/teamRole/operation/TeamRoleCommandSubmission.kt#L35): 같은 insert 후 canonical 조회 계약을 사용한다.
- [Project 접수](../../../../cowork-project/src/main/kotlin/com/cowork/project/domain/github/service/impl/UpdateGithubLabelPolicyServiceImpl.kt#L36): 동일하게 후속 잠금 조회를 수행하며 명시적 신규·기존 반환 타입은 없다.

## 할 일

- 신규와 기존 canonical operation을 구분하는 명시적 결과 타입·repository 구현을 정한다.
- 안전하게 저장 결과를 확보한 신규 경로에서만 조회를 생략한다.
- 중복·경합은 canonical 잠금 조회·hash 비교·기존 상태 반환을 유지한다.
- operation과 outbox의 같은 transaction을 유지하고 전역 affected-row 설정은 변경하지 않는다.
- channel에서 적용한 뒤 team·project의 적용 범위와 공통화 여부를 정한다.

## 검증

- 반환 타입·SQL·driver 의미와 신규 operation만 command를 만드는 분기를 정적으로 대조한다.
- 실제 경합의 canonical 결과와 발행 수는 운영 지표·수동 데이터 점검으로 확인한다.

## 완료 조건

- 신규 경로의 추가 조회가 제거되어 있고 호출자가 driver의 update count를 해석하지 않는다.
- 중복·경합은 실제 승자 operation을 기준으로 판단하며 outbox 원자성이 유지되어 있다.
- 세 모듈의 적용 범위가 정해져 있다.

## 점검 근거 (2026-10-08)

- 관련 PR: [#314](https://github.com/team-cowork/cowork-server/pull/314).
- [대조 코드](../../../../cowork-channel/src/main/kotlin/com/cowork/channel/domain/channelRolePolicy/operation/ChannelRolePolicyCommandSubmission.kt): channel·team·project 모두 insertPendingIfAbsent 뒤 canonical 잠금 조회를 유지한다. 정확성 보장은 이미 있고 신규 경로 조회 최적화는 없다.
- 판정: **미구현**. 신규·기존 구분 repository 결과 계약과 안전한 조회 생략을 남은 범위로 유지한다.
