# cowork-authorization

DataGSM OAuth2 PKCE 로그인, JWT 수명 주기, 로그인 세션에서 파생되는 presence를 담당합니다.
사용자 account/profile identity의 durable owner는 `cowork-user`입니다.

## 주요 동작

- `POST /auth/token`: DataGSM 인가 코드를 교환하고, user identity owner commit을 확인한 뒤 JWT 발급
- `POST /auth/refresh`: 리프레시 토큰 검증·회전과 새 토큰 쌍 발급
- `POST /auth/signout`: 리프레시 토큰 폐기와 마지막 세션 종료 시 offline event 기록
- `POST /events/datagsm`: HMAC 검증된 `student.updated`를 기존 `user.data.sync`로 전달

authorization은 user identity를 자체 source table에 저장하지 않습니다. 로그인 시
`tb_user_identity_operations`의 `PENDING` row와 `user.identity.command` outbox를 같은 MySQL
transaction으로 기록합니다. `cowork-user`가 account/profile과 `user.profile.event`, command result를
원자적으로 commit한 뒤 `SUCCEEDED` 결과를 보내야만 authorization이 refresh session과 presence를
저장하고 토큰을 응답합니다. `FAILED` 또는 timeout에서는 토큰과 세션을 만들지 않습니다.

result consumer는 shared MySQL operation row를 갱신하므로 로그인 요청을 처리한 replica와 결과를
소비한 replica가 달라도 동작합니다. operation/result 재전달은 canonical payload hash가 정확히 같은
경우만 허용하고, 같은 idempotency key 또는 operation ID의 상충 payload는 fail-closed 처리합니다.

## Kafka 계약

| 방향 | Topic | Key |
|---|---|---|
| produce | `user.identity.command` | user ID |
| consume | `user.identity.command-result` | operation UUID |
| produce | `user.identity.command-result-dlt` | rejected result의 원본 key |
| produce | `user.data.sync` | DataGSM student ID |
| produce | `user.presence.event` | user ID |

identity command는 schema version 1, UUID operation ID, 최대 128자의 idempotency key와 기존
`UpsertUserRequest`의 전체 account/profile 필드를 포함합니다. result는 `SUCCEEDED + userId` 또는
`FAILED + bounded error` 중 하나만 허용합니다.
잘못된 JSON/key/계약, unknown operation, 상충 result는 원본 key/payload와 source metadata를
`user.identity.command-result-dlt`에 기록한 뒤 offset을 진행합니다. DLT 발행 실패와 DB 오류는 재시도합니다.

`user.presence.event` outbox는 refresh session과 durable `tb_user_presence_states` 변경을 같은
transaction으로 commit하며 at-least-once 발행합니다. startup/주기 snapshot은 모든 partition에
완료 marker를 기록합니다.

## 의존성

- MySQL: identity command operation, refresh token, presence source, Kafka outbox, webhook idempotency
- Kafka: 위 네 topic
- HTTP: DataGSM OAuth API
- Config Server와 Eureka

## 환경 변수

Compose에서는 `APP_CONFIG_URL`, `APP_PROFILE`로 Config Server를 사용합니다. 직접 실행 시 같은 이름의
환경 변수가 config 값을 override합니다. 주요 Kafka 설정은 다음과 같습니다.

- `KAFKA_TOPIC_USER_SYNC`
- `KAFKA_TOPIC_USER_IDENTITY_COMMAND`
- `KAFKA_TOPIC_USER_IDENTITY_COMMAND_RESULT`
- `KAFKA_TOPIC_USER_IDENTITY_COMMAND_RESULT_DLT`
- `KAFKA_GROUP_ID_USER_IDENTITY_COMMAND_RESULT`
- `KAFKA_IDENTITY_COMMAND_TIMEOUT`
- `KAFKA_TOPIC_USER_PRESENCE`

일반 DB migration은 서비스 시작 시 실행합니다. zero-state에서는 빈 DB에 migration을 적용한 뒤 평소와
같은 command/result 경로로 첫 사용자를 생성합니다.
