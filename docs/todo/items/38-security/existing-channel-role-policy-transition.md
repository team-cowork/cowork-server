# 기존 역할·채널 정책의 운영 전환

- **서비스**: cowork-preference, cowork-team, cowork-channel, cowork-chat, 배포 운영
- **우선순위**: 🔴 높음
- **현재 상태**: `cowork-channel`과 `cowork-chat`의 읽기 경로는 정책 평가기를 호출하지만 기존 팀의 역할·채널 정책을 구성하는 전환 도구와 운영 절차는 정의되어 있지 않다
- **파생 원본**: [역할 기반 채널·메시지 읽기 권한 적용](../36-security/role-based-channel-message-read-authorization.md)

## 문제

`cowork-preference/src/main/resources/db/migration/V20__add_channel_role_policy_command_contract.sql`은
`tb_channel_role_policies`를 생성하지만 기존 역할과 채널에 대한 정책을 채우지 않는다. `PreferenceSnapshotPublisher`도
authoritative 저장소에 존재하는 정책만 발행하며, 정책이 0건이어도 snapshot completion marker를 발행한다. 따라서
projection readiness가 열렸다는 사실만으로 운영 데이터 전환이 끝났다고 판단할 수 없다.

`cowork-channel`과 `cowork-chat`의 평가기는 built-in `OWNER`만 role policy를 우회한다. `ADMIN`과 `MEMBER`는 사용자
정의 역할 할당과 채널별 정책이 없으면 `message_read`를 거부한다. 기존 팀에 정책을 구성하지 않은 채 인가를 활성화하면
projection이 정상이어도 `OWNER`가 아닌 사용자의 채널 읽기가 의도하지 않게 전부 차단될 수 있다.

PostgreSQL migration만으로는 `cowork-channel`의 MySQL이 소유한 채널 목록을 알 수 없다. 기존
`tb_team_role_definitions.permissions` 문자열 배열에도 `message_read`를 안전하게 추론할 정보가 없다. 또한 모든
`role × channel` 조합에 `message_read=false`를 넣으면 정책 부재 시 낮은 priority로 상속하는 의미를 명시적 거부로
바꾸므로 단순 일괄 backfill을 적용할 수 없다.

## 전환 계약

| 항목 | 계약 |
|------|------|
| built-in 역할 | `OWNER`는 role policy만 우회하고 기존 채널 멤버십 조건은 유지한다. `ADMIN`과 `MEMBER`에는 암묵적 allow를 부여하지 않는다 |
| 사용자 정의 역할 | 팀·채널·역할 조합마다 `allow`, `deny`, 정책 부재 유지를 운영자가 명시적으로 승인한다 |
| 정책 부재 | 낮은 priority 역할로 상속하고 끝까지 정책이 없으면 거부한다. 부재 상태를 일괄 `false`로 변환하지 않는다 |
| 전환 입력 | 각 도메인 소유 서비스에서 수집한 식별자를 하나의 versioned manifest로 고정하며 cross-service DB join을 사용하지 않는다 |
| 적용 경계 | 직접 SQL 대신 `cowork-preference`의 channel role policy command와 outbox 경계를 사용한다 |
| 활성화 순서 | authoritative 적용과 `preference.channel-role-policy.changed` projection 수렴을 확인한 뒤 읽기 인가 전환을 완료한다 |

## 할 일

### 전환 입력과 사전 점검

- built-in `OWNER`, `ADMIN`, `MEMBER`의 기본 동작과 멤버십 우회 범위를 운영 문서에 고정한다.
- 기존 팀별 채널과 사용자 정의 역할 조합을 `allow`, `deny`, 정책 부재 유지로 구분하는 versioned manifest를 정의한다.
- 기존 `tb_team_role_definitions.permissions` 값에서 `message_read`를 추론하지 않는다.
- 팀 멤버십, 사용자 정의 역할·할당, 팀 채널, 기존 정책을 각 소유 서비스에서 수집한다.
- 사용자별 effective policy를 계산해 읽을 수 있는 채널이 0개가 되는 non-`OWNER`, 존재하지 않는 참조, 동일 priority 충돌을 보고한다.
- 운영자가 검토하지 않은 팀을 전환 누락과 명시적인 기본 거부 승인으로 구분한다.

### 적용과 운영 전환

- 상태를 변경하지 않는 dry-run과 실제 적용이 같은 manifest를 사용하도록 전환 도구를 구현한다.
- 정책 변경을 `cowork-preference`의 authoritative command 경계를 통해 적용한다.
- 전환 버전, `teamId`, `channelId`, `roleId`, canonical permissions로 결정적인 `Idempotency-Key`를 생성한다.
- 모든 operation이 최종 상태가 되고 두 소비 서비스 projection이 수렴할 때까지 적용 진행 상태를 추적한다.
- 부분 실패 뒤의 재개, 멱등 재실행, rollback, maintenance 또는 cutover 순서를 운영 runbook에 기록한다.
- 팀별 적용 결과와 기본 거부 유지 승인을 운영 기록으로 남긴다.

## 검증

- dry-run이 DB, Kafka, operation 상태를 변경하지 않는지 확인한다.
- 같은 manifest를 반복 적용해도 authoritative 정책과 outbox 결과가 한 번 적용한 상태와 같은지 확인한다.
- 모든 operation이 `SUCCEEDED` 또는 운영자가 승인한 명시적 실패 상태인지 확인한다.
- `cowork-preference` 정책과 `cowork-channel`·`cowork-chat` projection의 키와 값이 일치하는지 확인한다.
- 공개·비공개 채널에서 `OWNER`, 정책 없는 `ADMIN`·`MEMBER`, allow 역할, deny 역할, 동일 priority 충돌을 확인한다.
- snapshot replay와 consumer 재시작 뒤에도 같은 effective permission이 유지되는지 확인한다.
- 실제 운영 데이터의 대상 개수와 전환 결과는 환경별 운영 기록에서 별도로 확인한다.

## 완료 조건

- built-in 역할의 기본값과 우회 범위가 두 평가기와 운영 문서에 일치되어 있다.
- 기존 각 팀은 승인된 정책 manifest가 적용되어 있거나 기본 거부 유지가 명시적으로 승인되어 있다.
- 전환 대상 정책이 authoritative 저장소와 `cowork-channel`·`cowork-chat` projection에 동일하게 반영되어 있다.
- 전환 도구에 dry-run, 멱등 재실행, 실패 재개, rollback 절차가 마련되어 있다.
- 전환 과정에서 의도하지 않은 전체 non-`OWNER` 차단이나 우회 허용이 발생하지 않는다.
- 전환은 cross-service DB 직접 join이나 기존 문자열 권한의 임의 추론에 의존하지 않는다.
