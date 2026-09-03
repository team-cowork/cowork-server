# Idempotency operation repository 반환 계약 최적화

- **서비스**: cowork-channel, cowork-team, cowork-project
- **우선순위**: 🟡 낮음
- **현재 상태**: MySQL operation 접수 경로가 원자적 insert-or-no-op 뒤 canonical row를 잠금 조회해 정확성을 보장하지만, 신규 삽입에도 조회 한 번이 추가됨
- **파생 원본**: [역할 기반 채널·메시지 읽기 권한 적용](../36-security/role-based-channel-message-read-authorization.md)

## 문제

`cowork-channel`의 `ChannelRolePolicyCommandSubmission`은 `insertPendingIfAbsent`로 `(actor_id, idempotency_key)`의
operation을 원자적으로 생성한 뒤 `findByActorAndIdempotencyKeyForUpdate`로 실제 저장된 행을 다시 조회한다. 같은 패턴이
`cowork-team`의 역할 command와 `cowork-project`의 GitHub 설정 command에도 존재한다. 중복 요청이나 동시 경합에서는
승리한 operation의 범위·요청 hash·상태를 읽어 기존 결과를 반환하거나 충돌을 거부해야 하므로 잠금 조회가 필요하다.

신규 삽입에서는 입력값을 이미 알고 있어 후속 조회를 생략할 여지가 있다. 그러나 현재 `@Modifying` native query의 `Int`
결과는 MySQL Connector/J의 affected-row 설정에 따라 신규 삽입과 duplicate no-op을 안정적으로 구분하지 못한다. 반환값만
분기하면 경합에서 패배한 요청도 자신이 신규 operation을 만들었다고 오판해 command를 중복 발행할 수 있다. 따라서 단순
분기가 아니라 DB dialect와 driver 의미를 캡슐화한 별도 repository 계약이 필요하다.

## 계약 방향

| 항목 | 결정 |
|------|------|
| 반환 모델 | 신규 operation과 기존 canonical operation을 구분하는 명시적 결과 타입을 정의한다 |
| 신규 삽입 | 저장된 operation을 안전하게 반환할 수 있을 때만 후속 잠금 조회를 생략한다 |
| 중복·경합 | 실제 승자 행을 `SELECT ... FOR UPDATE`로 조회해 요청 동일성과 현재 상태를 판정한다 |
| affected rows | `Int` update count나 전역 `useAffectedRows` 설정만으로 신규 여부를 판정하지 않는다 |
| 적용 범위 | `cowork-channel`에서 계약을 검증한 뒤 `cowork-team`, `cowork-project`의 동일 패턴 적용 여부를 결정한다 |
| 테스트 범위 | 회귀·통합 테스트 추가와 실행은 범위에서 제외하고 repository 계약의 단위 검증과 정적 확인만 수행한다 |

## 할 일

### Repository 계약

- 신규 삽입과 기존 operation을 구분하는 결과 타입을 정의한다.
- MySQL/JDBC 구현 세부 사항을 custom repository 내부에 격리한다.
- 중복·경합 경로가 canonical operation 잠금과 요청 hash 비교를 유지하도록 한다.
- 신규 삽입 경로에서만 불필요한 후속 조회를 제거한다.
- `cowork-team`, `cowork-project`의 동일 패턴을 비교하고 공통화 또는 모듈별 유지 결정을 문서화한다.

### 안전 조건

- operation mutation과 outbox 적재가 기존 로컬 transaction 안에서 유지되도록 한다.
- 전역 JDBC affected-row 설정을 변경해 다른 update 결과 의미에 영향을 주지 않는다.
- 회귀·통합 테스트를 새로 추가하거나 실행하지 않고, 결과 타입 분기와 요청 동일성 판정을 단위 수준에서 확인한다.

## 검증

- custom repository가 신규·기존 결과를 모호하지 않은 타입으로 반환하는지 단위 검증한다.
- 중복 경로에서 canonical `operationId`, 요청 hash, 상태를 사용하도록 정적으로 확인한다.
- 신규 operation만 outbox command를 생성하는 분기 구조를 단위 수준에서 확인한다.
- 변경된 SQL과 Connector/J affected-row 의미가 전역 설정에 의존하지 않는지 문서와 설정으로 확인한다.

## 완료 조건

- 신규 삽입 경로는 canonical operation을 안전하게 확보하면서 추가 잠금 조회를 실행하지 않는다.
- 중복·동시 경합 경로는 실제 승자 operation을 기준으로 기존 결과 반환 또는 충돌을 결정한다.
- 신규 operation 하나당 outbox command가 하나만 만들어지는 구조가 유지되어 있다.
- repository 호출자는 MySQL/JDBC의 affected-row 차이를 직접 해석하지 않는다.
- 세 모듈의 적용 범위와 공통화 여부가 문서화되어 있다.
