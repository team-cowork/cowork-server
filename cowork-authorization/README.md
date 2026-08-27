# cowork-authorization

## 역할

DataGSM OAuth2 PKCE 로그인과 JWT 수명 주기를 담당하는 인증 서비스입니다.

- `POST /auth/token`: 인가 코드를 DataGSM 토큰·사용자 정보와 교환하고 cowork JWT 발급
- `POST /auth/refresh`: 저장된 리프레시 토큰을 검증·회전하고 새 토큰 쌍 발급
- `POST /auth/signout`: 리프레시 토큰 폐기 및 WebSocket 인증 쿠키 삭제
- `POST /events/datagsm`: HMAC 검증된 DataGSM 사용자 변경 웹훅을 받아 `user.data.sync` 발행
- 로그인 시 `cowork-user`에 identity/profile 정보만 upsert
- refresh session과 사용자별 presence source, `user.presence.event` outbox를 같은 MySQL transaction으로 변경
- 로그인·갱신 응답에서 `cowork_ws_token` 쿠키(`HttpOnly`, `Secure`, `Path=/ws`)도 발급하여 Gateway의 `/ws/**` 인증 지원

로그인 시의 `cowork-user` upsert는 의도적인 동기 HTTP 예외입니다. 사용자 저장이 완료되기 전에 토큰을 발급하면 첫 인증 요청이 사용자 생성보다 앞설 수 있으므로, 명시적 로그인 준비 완료(ack) 프로토콜 없이는 비동기 이벤트로 바꾸지 않습니다. 이 command에는 online/offline을 싣지 않으며 presence의 유일한 authority는 `user.presence.event`입니다.
배포 전환 중 구버전 cowork-user가 `/internal/users/{id}`를 아직 제공하지 않으면 404에 한해서만 이전
`/users/{id}` command로 재시도합니다. 이 legacy request에만 구버전 user가 필요로 하는 `status=online`을
포함하며 새 user는 이를 무시합니다. 400/5xx는 command 중복을 막기 위해 fallback하지 않습니다.
이는 login path용 임시 shim일 뿐 mixed-version presence/custom-status semantics나 무중단 rolling을
보장하지 않습니다. 이 전환 릴리스는 user/auth login traffic을 drain하고 구버전 user replica를 모두
종료한 다음 authorization과 cowork-team snapshot source의 두 state topic marker를 확인하고 user
migration/consumer를 시작하는 순서로 maintenance cutover합니다.

## 스택

- Go 1.26 + Gin
- GORM + MySQL
- Kafka, Eureka Client, Spring Config 호환 클라이언트

## 포트와 운영 엔드포인트

- 포트: `8081`
- Health: `/health`
- Prometheus: `/metrics`
- Swagger UI: `/swagger/index.html`

## 의존성

- MySQL: 리프레시 토큰, 사용자별 durable presence state, Kafka outbox, 처리한 웹훅 이벤트 저장
- Kafka produce: `user.data.sync`, `user.presence.event`

`tb_user_presence_states`는 사용자별 unexpired session 수와 online/offline, 마지막 변경 시각(UTC
microseconds)을 보존합니다. 최초 유효 session에서만 online, 마지막 session revoke/expiry에서만 offline을
발행하며 offline 행도 삭제하지 않습니다. 같은 사용자 session 전이는 presence 행 lock으로 직렬화하고,
서로 다른 전이가 같은 MySQL microsecond에 발생하거나 wall clock이 뒤로 이동하면 저장된 시각보다 1 microsecond
큰 logical mutation timestamp를 사용하여 마지막 session 상태가 항상 최신 record가 되게 합니다.
refresh rotation은 기존 token을 lock한 transaction 안에서 유효성 확인·조건부 삭제·후속 token 삽입을 끝내므로
동시 refresh/logout 패자는 successor를 만들 수 없습니다. V6 migration은 기존 refresh token 사용자를
`UTC_TIMESTAMP(6)` 기준으로 backfill하며, token이 전혀 없었던 사용자는 cowork-user의 offline baseline으로
해석합니다. DataGSM account role은 `ADMIN → ADMIN`, `USER → MEMBER`로만 매핑하고 refresh row의
`platform_role`에 보존합니다. V7 이전 legacy session은 추론할 원본 role이 없으므로 최소 권한인 `MEMBER`로
backfill합니다.

서비스는 공급된 MySQL DSN을 `parseTime=true`, `loc=UTC`, session `time_zone='+00:00'`으로 정규화합니다.
따라서 `DATETIME(6)` session 만료와 presence mutation version은 로컬 호스트 시간대와 무관하게 같은 UTC
microsecond 계약을 사용합니다.

`user.presence.event` outbox는 낮은 미커밋 auto-increment ID를 건너뛰지 않도록 locking read로 relay하며
at-least-once로 발행합니다. startup/주기 snapshot은 모든 durable online/offline 행을 각 행에 저장된
`occurredAt`으로 먼저 outbox에 넣고, 이어서 각 Kafka partition에
`__cowork_projection_snapshot_complete__:{partition}` / `PROJECTION_SNAPSHOT_COMPLETED` marker를 명시 partition으로
적재합니다. cowork-user는 모든 partition marker와 startup high-watermark를 통과하기 전까지 projection 의존 readiness를
열지 않습니다.
- HTTP: DataGSM OAuth API, `cowork-user`
- Eureka, Config Server(Compose 기동 시 필수)

## 환경 변수

| 공급원 | 설정 |
|---|---|
| Compose | `APP_CONFIG_URL`, `APP_PROFILE` |
| Config Server | 포트, DataGSM endpoint, 토큰 TTL, Kafka, Eureka, User 서비스 URL |
| Vault | `DB_DSN`, `DATAGSM_CLIENT_ID`, `DATAGSM_WEBHOOK_SECRET`, `JWT_SECRET` |

직접 실행 시 같은 이름의 환경 변수로 값을 override할 수 있습니다. `APP_CONFIG_URL`을 지정한 상태에서 Config Server 조회에 실패하면 기동하지 않습니다.
