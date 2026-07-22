# cowork-notification

## 역할

여러 서비스가 발행한 알림 요청을 사용자 설정에 맞춰 전달하는 서비스입니다.

- Kafka `notification.trigger` 이벤트 소비
- `cowork-preference`, `cowork-team`, `cowork-user` 조회 후 수신 대상·표시 정보 결정
- Firebase Cloud Messaging(FCM) 푸시 발송
- 디바이스 토큰 등록·삭제
- SSE(Server-Sent Events) 실시간 알림 스트림

## 스택

- Go 1.26 + Chi
- GORM + MySQL
- Kafka, Firebase Admin SDK, Eureka Client

## 포트와 엔드포인트

- 포트: `8086`
- API: `POST /notifications/tokens`, `DELETE /notifications/tokens/{token}`, `GET /notifications/stream`
- Health: `/health`
- Prometheus: `/metrics`
- Swagger UI: `/swagger/index.html`

API 요청의 사용자는 Gateway가 전달한 `X-User-Id` 헤더로 식별합니다. SSE 라우트는 Gateway에서 응답 timeout을 비활성화합니다.

## 의존성

- MySQL: 디바이스 토큰 저장
- Kafka consume: `notification.trigger`
- HTTP: `cowork-preference`, `cowork-team`, `cowork-user`
- Firebase FCM, Eureka, Config Server

## 환경 변수

| 공급원 | 설정 |
|---|---|
| Compose | `APP_CONFIG_URL`, `APP_PROFILE`, Firebase JSON Docker secret mount |
| Config Server | 포트, Kafka, Eureka, FCM 파일 경로, Preference/Team/User URL |
| Vault | `db.dsn` |

`APP_CONFIG_URL`을 지정한 상태에서 Config Server 조회에 실패하면 기동하지 않습니다. Firebase 서비스 계정 JSON은 문자열 환경변수가 아니라 `/run/secrets/firebase-credentials.json`에 read-only로 마운트됩니다.
