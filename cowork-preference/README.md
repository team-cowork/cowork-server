# cowork-preference

## 역할

계정·팀·프로젝트·채널 설정과 사용자 정의 역할을 관리하는 Vert.x 서비스입니다.

- 계정 상태와 만료 시간, 테마·언어·날짜/시간 표시 설정
- 팀 설정과 텍스트/음성 채널 설정
- 계정별 채널 알림 on/off 설정
- 프로젝트 역할 정의·멤버 역할 할당
- 팀 사용자 정의 역할 정의·수정·할당과 멤버별 역할 조회
- 설정 Redis 캐시와 상태 만료 처리
- 상태·팀 설정·채널 알림·팀 역할 projection 이벤트 발행
- compacted `team.member.event`의 논리적 삭제 상태를 소비해 팀 역할/할당 정리

## 스택

- Kotlin 2.4 / Java 25 + Vert.x 5 Coroutines
- Vert.x PostgreSQL Client + PostgreSQL + Flyway
- Vert.x Redis Client, Vert.x Kafka Client
- Eureka Client, Micrometer Prometheus
- Amper(`module.yaml`)

## 포트와 엔드포인트

- 포트: `9001`
- API: `/preferences/**`
- 내부 팀 역할 command: `/internal/preferences/team/**/roles` (Gateway 비노출, `cowork-team` 전용). 역할 조회와 멤버 이탈 정리는 Kafka projection을 사용한다.
- Liveness: `/health`
- Readiness: `/health/ready`
- Prometheus: `/metrics`
- OpenAPI JSON: `/swagger/doc.json`

API 전체 경로는 `src/main/resources/openapi.json`에서 확인합니다.

## 이벤트와 의존성

- Kafka produce: `preference.status.changed`, `preference.team.setting.changed`, `preference.channel-notification.changed`, `preference.team-role.changed`
- Kafka consume: `team.member.event`
- PostgreSQL: 설정과 팀·프로젝트 역할
- Redis: 리소스 설정과 채널 알림 캐시
- Config Server: 기동 시 필수(3회 조회 실패 후 종료)
- Eureka: 서비스 등록과 heartbeat

`team.member.event`은 Kafka broker group offset이 아니라 PostgreSQL의
`tb_projection_consumer_checkpoints`를 복구 기준으로 사용합니다. 역할 정리와 다음 offset을 같은 트랜잭션에
저장하고, 잘못된 계약 레코드는 `tb_projection_quarantine`에 격리된 뒤에만 checkpoint를 진전시킵니다.
`DELETE + MEMBER|ADMIN`은 해당 멤버의 역할 할당을 정리합니다. 현재 팀 도메인에서 OWNER는 개별 제거나 역할
변경이 금지되므로 `DELETE + OWNER`는 팀 삭제의 상태 표현이며, 팀 역할 정의와 모든 할당을 함께 정리합니다.
`UPSERT`는 cleanup 없이 checkpoint만 진전시킵니다.
기동 시 모든 partition의 end offset과 Kafka topic UUID를 공유 barrier로 저장하며, 공유 checkpoint가 barrier에 도달하고
각 partition의 source snapshot completion marker를 소비하기 전에는 `/health/ready`와 팀 역할 API가 `503`을 반환하고
Eureka에도 등록하지 않습니다. 기존 checkpoint/marker가 retention 범위 밖이거나 같은 이름의 topic UUID가 달라진 경우에는
자동으로 보정하지 않고 소비와 readiness를 닫아 유실된 projection 상태를 운영자가 먼저 대조할 수 있게 합니다.

네 개의 발행 토픽(`preference.channel-notification.changed`, `preference.team-role.changed`,
`preference.status.changed`, `preference.team.setting.changed`)은 설정 변경과
`tb_preference_event_outbox` 적재를 같은 PostgreSQL 트랜잭션으로 커밋합니다. dispatcher는 PostgreSQL
transaction advisory lock으로 여러 replica 중 한 번에 하나만 가장 오래된 미발행 레코드를 전송합니다. 모든 outbox producer도
mutation과 row lock보다 먼저 같은 advisory lock을 획득해 미커밋된 낮은 sequence ID를 dispatcher가 건너뛰지 못하게 하며,
Kafka 전송 성공 뒤에만 발행 완료로 표시합니다. 전송 뒤 완료 표시 전에 장애가 나면 같은 레코드를 재전송하므로
소비자는 안정적인 aggregate key와 `occurredAt`을 기준으로 중복을 허용해야 합니다. 발행 완료 레코드는 7일 뒤
정리됩니다. compacted projection snapshot도 source row를 `FOR SHARE`로 잠근 같은 트랜잭션에서 outbox에 넣어,
동시 update/delete 뒤 오래된 live snapshot이 더 최신 Kafka record로 남지 않게 합니다. snapshot이 끝나면
`preference.channel-notification.changed`와 `preference.team-role.changed`의 모든 partition에 명시 partition completion marker를
outbox 마지막에 적재합니다.

## 환경 변수

| 공급원 | 설정 |
|---|---|
| Compose | `CONFIG_SERVER_URL`, `SPRING_PROFILES_ACTIVE` |
| Config Server | 포트, PostgreSQL host/DB/schema/pool, Redis, Kafka, Eureka |
| Vault | `preference.db.username`, `preference.db.password` |

Config Server를 3회 조회하지 못하면 종료합니다. `PORT`, `POSTGRES_*`, `REDIS_*`, `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_CONSUMER_GROUP_ID`, `KAFKA_TOPIC_TEAM_MEMBER_EVENT`, `EUREKA_*` 직접 환경변수는 중앙 설정보다 높은 우선순위로 적용됩니다.
