# cowork-authorization

## 역할

DataGSM OAuth2 PKCE 로그인과 JWT 수명 주기를 담당하는 인증 서비스입니다.

- `POST /auth/token`: 인가 코드를 DataGSM 토큰·사용자 정보와 교환하고 cowork JWT 발급
- `POST /auth/refresh`: 저장된 리프레시 토큰을 검증·회전하고 새 토큰 쌍 발급
- `POST /auth/signout`: 리프레시 토큰 폐기 및 WebSocket 인증 쿠키 삭제
- `POST /events/datagsm`: HMAC 검증된 DataGSM 사용자 변경 웹훅을 받아 `user.data.sync` 발행
- 로그인 시 `cowork-user`에 사용자 정보를 upsert
- 로그인·갱신 응답에서 `cowork_ws_token` 쿠키(`HttpOnly`, `Secure`, `Path=/ws`)도 발급하여 Gateway의 `/ws/**` 인증 지원

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

- MySQL: 리프레시 토큰과 처리한 웹훅 이벤트 저장
- Kafka produce: `user.data.sync`
- HTTP: DataGSM OAuth API, `cowork-user`
- Eureka, Config Server(Compose 기동 시 필수)

## 환경 변수

| 공급원 | 설정 |
|---|---|
| Compose | `APP_CONFIG_URL`, `APP_PROFILE` |
| Config Server | 포트, DataGSM endpoint, 토큰 TTL, Kafka, Eureka, User 서비스 URL |
| Vault | `DB_DSN`, `DATAGSM_CLIENT_ID`, `DATAGSM_WEBHOOK_SECRET`, `JWT_SECRET` |

직접 실행 시 같은 이름의 환경 변수로 값을 override할 수 있습니다. `APP_CONFIG_URL`을 지정한 상태에서 Config Server 조회에 실패하면 기동하지 않습니다.
