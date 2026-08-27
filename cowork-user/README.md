# cowork-user

사용자 account/profile과 사용자 설정 프로필의 durable owner입니다. Plug/Cowboy HTTP API, Ecto/MySQL,
Kafka consumer/outbox, Redis 표시 이름 cache, S3 호환 프로필 이미지를 사용합니다.

## 소유권

- `tb_accounts`, `tb_profiles`, profile role과 name/GitHub 값은 `cowork-user`가 소유합니다.
- `PATCH /users/me`는 기존 계약대로 nickname/description/roles와 선택적인 `name`, `github_id`를 수정합니다.
- authorization은 account source를 보유하지 않고 로그인에 필요한 complete upsert command만 보냅니다.
- `user.data.sync`의 DataGSM `student.updated`는 이미 존재하는 account만 부분 갱신합니다.

## 로그인 identity command

`user.identity.command` consumer는 schema version, Kafka key, UUID operation ID, idempotency key와 전체
account 필드를 엄격히 검증합니다. 새 command는 다음 변경을 하나의 MySQL transaction으로 commit합니다.

1. account/profile upsert
2. 현재 presence projection 재적용
3. `user.profile.event` outbox
4. `tb_user_identity_command_inbox`의 command hash와 최종 result
5. `user.identity.command-result` outbox

따라서 authorization이 `SUCCEEDED`를 관측했다면 account/profile owner commit이 먼저 완료된 상태입니다.
동일 operation ID와 idempotency key의 정확한 command 재전달은 저장한 result를 outbox에 다시 넣습니다.
상충 payload 재사용은 account를 변경하거나 성공 result를 만들지 않고 fail-closed 처리합니다. Kafka publish는
DB mutation 안에서 직접 수행하지 않으며 공용 `tb_kafka_outbox` relay가 at-least-once로 전달합니다.

## API

- 내 프로필 조회·수정과 custom status 변경
- 사용자 단건·batch 조회, GitHub login 역조회, 팀 범위 검색
- presigned URL 기반 프로필 이미지 업로드·확정·삭제
- health, Prometheus, OpenAPI/Swagger

인증이 필요한 public HTTP 요청은 Gateway가 전달한 `X-User-Id`를 사용합니다. JWT를 직접 검증하지
않습니다. 예전 authorization용 `PUT /internal/users/{id}`와 `PUT /users/{id}` command route는 없습니다.

## Kafka

| 방향 | Topic | 역할 |
|---|---|---|
| consume | `user.identity.command` | 로그인 account/profile owner command |
| produce | `user.identity.command-result` | owner transaction 결과 |
| consume | `user.data.sync` | DataGSM 학생 정보 부분 갱신 |
| consume | `team.member.event` | 팀 멤버 projection |
| consume | `user.presence.event` | online/offline projection |
| produce | `user.profile.event` | 공개 사용자 profile state와 snapshot |

team/presence state consumer는 MySQL projection과 Kafka checkpoint를 같은 transaction으로 갱신합니다.
projection readiness와 Eureka 등록은 두 state source가 준비될 때까지 fail-closed하며, identity command와
user sync는 state projection의 ownership을 바꾸지 않습니다.

주요 설정 이름은 다음과 같습니다.

- `KAFKA_TOPIC_USER_IDENTITY_COMMAND`
- `KAFKA_GROUP_ID_USER_IDENTITY_COMMAND`
- `KAFKA_TOPIC_USER_IDENTITY_COMMAND_RESULT`
- `KAFKA_TOPIC_USER_SYNC`, `KAFKA_GROUP_ID`
- `KAFKA_TOPIC_TEAM_MEMBER`, `KAFKA_GROUP_ID_TEAM_MEMBER`
- `KAFKA_TOPIC_USER_PRESENCE`, `KAFKA_GROUP_ID_USER_PRESENCE`
- `KAFKA_TOPIC_USER_PROFILE`

## 시작

컨테이너 entrypoint가 Config Server의 DB 설정을 읽고 Flyway migration을 수행한 뒤 Elixir release를
시작합니다. 빈 DB에서는 V1부터 현재 migration까지 적용한 뒤 첫 로그인 command가 owner account/profile을
생성합니다. Config Server와 Eureka는 기존 서비스 발견 경로로 계속 사용합니다.
