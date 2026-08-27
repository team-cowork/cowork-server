# cowork-user

## 역할

사용자 계정과 공개 프로필을 관리하는 서비스입니다.

- 내 프로필 조회·수정과 상태 메시지 변경
- 사용자 단건·batch 조회와 팀 범위 사용자 검색
- SeaweedFS(S3 호환) presigned URL 기반 프로필 이미지 업로드·확정·삭제
- Kafka `user.data.sync`, `team.member.event`, `user.presence.event` 소비
- 공개 프로필 변경과 주기 snapshot을 `user.profile.event`로 발행
- Redis에 표시 이름 캐시

## 스택

- Elixir 1.18 + Plug/Cowboy
- Ecto + MySQL, Flyway(Docker entrypoint에서 migration 실행)
- brod(Kafka), Redix, ExAws S3(SeaweedFS)
- Eureka Client와 Spring Config 호환 클라이언트

## 포트와 엔드포인트

- 포트: `8082`
- API: `/users/**`
- Liveness: `/actuator/health`
- Readiness: `/actuator/health/readiness`
- Prometheus: `/actuator/prometheus`
- OpenAPI JSON / Swagger UI: `/v3/api-docs`, `/swagger-ui.html`

인증이 필요한 요청은 Gateway가 전달한 `X-User-Id`를 사용합니다. `PUT /internal/users/{userId}`는 authorization 서비스의 동기화용 identity/profile upsert command이며 Gateway public route에는 노출하지 않습니다. 이 command가 보낸 legacy presence 필드는 무시하고, 이미 도착한 `user.presence.event` projection을 보존·재적용합니다.
OpenAPI의 public JSON 성공 응답은 Gateway 실제 계약인
`{status:"OK",code:200,message:"OK",data:...}` envelope를 명시합니다.
login path 전환을 위해 이전 `/users/{userId}` PUT alias도 한시적으로 유지하지만 Gateway user route는
PUT method를 전달하지 않으므로 외부 호출은 차단됩니다. 모든 authorization instance가 internal path를
사용하는 버전으로 교체된 뒤 이 alias와 client fallback을 함께 제거할 수 있습니다.
이 alias는 mixed-version presence/custom-status semantics나 무중단 rolling을 보장하지 않습니다.
V13/V14 적용 전 user/auth login traffic을 drain하고 모든 구버전 user replica를 종료해야 합니다.
그 뒤 새 authorization과 새 cowork-team을 배포하여 `user.presence.event`와 `team.member.event`의
모든 partition snapshot marker를 확인하고 새 user를 migrate/start합니다. user readiness가 UP이 된
뒤에만 traffic을 다시 엽니다.

## 의존성

- MySQL: 계정·프로필 데이터
- Kafka consume: `user.data.sync`, `team.member.event`, `user.presence.event`
- Kafka produce: `user.profile.event`
- Redis: 표시 이름 캐시
- SeaweedFS: 프로필 이미지
- Eureka, Config Server

`team.member.event`와 `user.presence.event`는 MySQL projection과 같은 transaction에서 Kafka
`next_offset` checkpoint를 갱신합니다. 각 source가 startup/주기 snapshot 끝에 partition별
`PROJECTION_SNAPSHOT_COMPLETED` marker를 발행하며, cowork-user는 marker 관측 상태도 같은
transaction에 저장합니다. 시작 시점의 두 topic 전체 partition high-watermark를 넘고 모든
partition marker를 관측하기 전에는 readiness와 Eureka 등록을 열지 않습니다. 따라서 신규 topic이
아직 snapshot으로 채워지지 않은 최초 배포에서도 빈 projection을 정상 상태로 오판하지 않습니다.
Gateway를 우회한 direct-port 요청도 status를 포함하는 public GET(`/users/me`, 사용자 단건·검색·GitHub
역조회)은 같은 readiness가 열릴 때까지 503을 반환합니다. health/metrics/OpenAPI, 표시 이름 batch,
쓰기 요청과 authorization 내부 upsert command는 startup coupling을 피하기 위해 이 gate에서 제외합니다.
readiness의 checkpoint/marker DB 확인은 500ms 주기의 단일 background refresh가 수행하고 public read는
read-concurrent ETS cache만 확인하므로 요청량이 Kafka projection DB query 수를 증가시키지 않습니다.

계약이 잘못된 domain event나 snapshot marker, JSON decode 실패는 원문 Kafka key/payload와 원인을
`tb_kafka_projection_quarantine`에 저장한 뒤 같은 DB transaction에서만 checkpoint를 전진시킵니다.
projection 또는 quarantine/checkpoint 저장 실패는 transient infrastructure 오류로 분류하여 어떤 offset도
전진시키지 않습니다.

계정의 `status`/`presence_updated_at`은 authorization의 authoritative presence projection 전용이며,
사용자가 설정하는 커스텀 상태는 별도 `custom_status`에 저장합니다. presence consumer와 로그인 upsert는
`custom_status`를 변경하지 않습니다. 최초 rollout migration은 기존 `online`/`offline` 외의 값만
`custom_status`로 옮기고, projection이 있는 계정은 저장된 projection으로 presence를 재구성하며,
projection이 없는 계정은 오래된 offline baseline으로 초기화합니다. authorization은 첫 session부터
사용자별 durable state를 만들고 마지막 session 이후에도 offline 행을 유지하므로 이후 신규 consumer
snapshot에서 삭제된 session 사용자가 online으로 부활하지 않습니다.
사용자 검색의 `status` 필터는 `online|offline` presence 전용이고, 사용자 지정 값은 별도
`custom_status` 필터로 조회합니다.

`user.profile.event`의 profile snapshot과 partition별 완료 marker는 모두 `tb_kafka_outbox`에
순서대로 적재됩니다. relay는 명시 partition marker까지 broker 확인을 받은 뒤에만 outbox에서
제거합니다.

brod가 사용하는 Kafka Metadata v2에는 topic UUID가 없어 cowork-user 자체적으로 같은 이름의 topic
재생성을 확정 탐지할 수 없습니다. 운영에서는 state topic 이름과 Kafka data volume을 immutable로
취급합니다. Kafka cluster/data volume을 교체할 때는 `tb_team_member_projections`,
`tb_user_presence_projections`, `tb_kafka_projection_offsets`, `tb_kafka_projection_barriers`,
`tb_kafka_projection_quarantine`을 함께
재구축한 뒤 트래픽을 다시 열어야 합니다. checkpoint/marker가 현재 retained offset 범위를 벗어나면
서비스는 자동 복구를 시도하지 않고 fail-closed 상태를 유지합니다.

## 환경 변수

| 공급원 | 설정 |
|---|---|
| Compose | `APP_CONFIG_URL`, `APP_PROFILE` |
| Config Server | 포트, DB host/port/name와 Flyway URL, Kafka topic/group, S3(SeaweedFS) endpoint, Redis, Eureka |
| Vault | `DB_USERNAME`, `DB_PASSWORD`, `SECRET_KEY_BASE`, S3 access/secret key |

컨테이너 entrypoint가 Config Server 설정을 먼저 읽고 Flyway migration을 수행한 뒤 Elixir release를 시작합니다. Config Server 조회 실패 또는 필수 DB/`SECRET_KEY_BASE` 누락 시 즉시 종료합니다.
