# cowork-preference

## 역할

서비스별로 흩어지면 안 되는 구성 가능한 설정 값을 PostgreSQL에서 전담 관리하는 Vert.x 서비스입니다.

- 계정 상태와 만료 시간, 테마·언어·날짜/시간 표시 설정
- 팀 설정과 텍스트/음성 채널 설정
- 계정별 채널 알림 on/off 설정
- 프로젝트 역할 정의·멤버 역할 할당
- 팀 사용자 정의 역할의 정의(이름·색상·우선순위·멘션 가능 여부·권한)와 멤버 할당
- GitHub 저장소의 `label_auto_apply` 설정(값이 없거나 삭제된 경우 기본값 `true`)
- 설정 Redis 캐시와 상태 만료 처리
- 설정·역할 full-state와 command 처리 결과 발행

팀의 기본 멤버십 역할 `OWNER`/`ADMIN`/`MEMBER`는 `cowork-team` 소유입니다. 사용자 정의 역할과 그 권한·할당,
향후 역할에 종속되는 리소스 설정은 `cowork-preference` 소유로 유지합니다. 현재 구현에는 역할별 채널 가시성 같은
`role × resource` 설정 스키마가 아직 없습니다.

## 스택

- Kotlin 2.4 / Java 25 + Vert.x 5 Coroutines
- Vert.x PostgreSQL Client + PostgreSQL + Flyway
- Vert.x Redis Client, Vert.x Kafka Client
- Eureka Client, Micrometer Prometheus
- Amper(`module.yaml`)

## 포트와 엔드포인트

- 포트: `9001`
- API: `/preferences/**`
- Liveness: `/health`
- Readiness: `/health/ready`
- Prometheus: `/metrics`
- OpenAPI JSON: `/swagger/doc.json`

API 전체 경로는 `src/main/resources/openapi.json`에서 확인합니다.
팀 사용자 정의 역할의 외부 API는 `cowork-team`에 있으며, 이 서비스에는 해당 역할의 내부 REST API가 없습니다.
GitHub 저장소 설정도 직접 HTTP로 노출하지 않습니다. 접근 권한을 검증한 `cowork-project`의 공개 API가 Kafka command로
변경을 요청하는 유일한 진입 경로입니다.

## 이벤트와 의존성

- Kafka consume: `team.member.event`, `preference.team-role.command`, `preference.github-repo.setting.command`
- Kafka produce: `preference.status.changed`, `preference.team.setting.changed`,
  `preference.channel-notification.changed`, `preference.team-role.changed`,
  `preference.team-role.command-result`, `preference.github-repo.setting.state`,
  `preference.github-repo.setting.result`
- PostgreSQL: 구성 가능한 설정, 프로젝트 역할, 팀 사용자 정의 역할·할당, command inbox와 outbox
- Redis: 리소스 설정 캐시와 분산 작업 잠금
- Config Server: 기동 시 필수(3회 조회 실패 후 종료)
- Eureka: `team.member.event` projection readiness가 준비된 동안에만 등록·heartbeat

상태 변경은 authoritative PostgreSQL mutation과
`tb_preference_event_outbox` 적재를 같은 PostgreSQL 트랜잭션으로 커밋합니다. dispatcher는 PostgreSQL
transaction advisory lock으로 여러 replica 중 한 번에 하나만 가장 오래된 미발행 레코드를 전송합니다. 모든 outbox producer도
mutation과 row lock보다 먼저 같은 advisory lock을 획득해 미커밋된 낮은 sequence ID를 dispatcher가 건너뛰지 못하게 하며,
Kafka 전송 성공 뒤에만 발행 완료로 표시합니다. 전송 뒤 완료 표시 전에 장애가 나면 같은 레코드를 재전송하므로
소비자는 안정적인 aggregate key와 `occurredAt`을 기준으로 중복을 허용해야 합니다. 발행 완료 레코드는 7일 뒤
정리됩니다. compacted projection snapshot도 source row를 `FOR SHARE`로 잠근 같은 트랜잭션에서 outbox에 넣어,
동시 update 뒤 오래된 snapshot이 더 최신 Kafka record로 남지 않게 합니다. snapshot이 끝나면
state topic인 `preference.channel-notification.changed`, `preference.team-role.changed`,
`preference.github-repo.setting.state`에는 시작 시점과 주기적으로 full-state snapshot과 partition completion marker를
적재합니다. command/result 토픽은 compact하지 않는 action stream입니다. 채널 알림 상태는 PostgreSQL에 영구
upsert하며 삭제 API가 없으므로 tombstone이 필요하지 않습니다. `state_occurred_at`은 DB trigger가 이전 값보다 최소
1 microsecond 크게 만든 영속 상태 버전이고,
기존 writer 호환을 위해 `updated_at`도 같은 값으로 유지합니다.
mutation event와 주기 snapshot이 모두 이 값을 `occurredAt`으로 사용합니다. 채널 알림 GET은 동시 PUT 뒤 오래된
cache 값이 되살아나는 것을 막기 위해 authoritative PostgreSQL을 직접 읽습니다.

팀 역할 command는 durable inbox로 중복을 제거하고, `team.member.event`의 영속 projection으로 actor/target 멤버십과
권한을 다시 검증합니다. 역할 mutation, `preference.team-role.changed`, terminal command result, inbox 기록은 한
트랜잭션입니다. 역할 할당은 검증한 멤버십 버전에 귀속되며, 팀 삭제 tombstone 이후의 대기 command는 역할을 다시 만들 수
없습니다. GitHub 저장소 설정 `UPDATE` command도 같은 방식으로 설정 mutation, state/result outbox, inbox를 원자적으로
커밋합니다. 저장소 연결 삭제가 보내는 `DELETE` command는 설정 삭제, state DELETE, inbox만 원자적으로 커밋하며 대기 중인
공개 operation이 없으므로 result를 발행하지 않습니다. 두 command 모두 commit 뒤 Redis cache를 갱신하거나 무효화합니다.
`/health/ready`와 Eureka 등록은 팀 멤버 snapshot/checkpoint가 현재 Kafka
generation을 따라잡기 전까지 닫혀 있습니다.

## 환경 변수

| 공급원 | 설정 |
|---|---|
| Compose | `CONFIG_SERVER_URL`, `SPRING_PROFILES_ACTIVE` |
| Config Server | 포트, PostgreSQL host/DB/schema/pool, Redis, Kafka, Eureka |
| Vault | `preference.db.username`, `preference.db.password` |

Config Server를 3회 조회하지 못하면 종료합니다. `PORT`, `POSTGRES_*`, `REDIS_*`, `KAFKA_BOOTSTRAP_SERVERS`,
`KAFKA_GROUP_ID_TEAM_MEMBER_PROJECTION`, `KAFKA_TOPIC_TEAM_MEMBER_EVENT`, `KAFKA_GROUP_ID_TEAM_ROLE_COMMAND`,
`KAFKA_GROUP_ID_GITHUB_REPO_SETTING_COMMAND`, `EUREKA_*` 직접 환경변수는 중앙 설정보다 높은 우선순위로 적용됩니다.
Preference command/state/result 토픽 이름은 서비스 간 계약이므로 환경별 override를 허용하지 않습니다.
