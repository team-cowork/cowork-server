# cowork-notification

## 역할
알림 서비스.
- Kafka `notification.trigger` 이벤트 소비 후 FCM 푸시 알림 발송
- SSE(Server-Sent Events) 기반 실시간 알림 스트림 제공

## 스택
- Go
- Firebase FCM
- MySQL + GORM
- Eureka Client

## 포트
`8086`

## 의존성
- Eureka, Config Server
- Kafka consume: `notification.trigger`
- HTTP: `cowork-preference` (알림 설정 조회), `cowork-team`, `cowork-user`

## 환경변수
| 변수 | 설명 |
|---|---|
| `DB_DSN` | MySQL DSN |
| `KAFKA_BROKERS` | Kafka 브로커 주소 |
| `KAFKA_TOPIC_NOTIFICATION` | 구독 토픽명 |
| `KAFKA_GROUP_ID` | 컨슈머 그룹 ID |
| `FCM_CREDENTIALS_FILE` | Firebase 서비스 계정 키 파일 경로 |
| `PREFERENCE_SERVICE_URL` | cowork-preference URL |
| `TEAM_SERVICE_URL` | cowork-team URL |
| `USER_SERVICE_URL` | cowork-user URL |
| `APP_CONFIG_URL` | Config Server URL (선택) |
| `APP_PROFILE` | 활성 프로파일 (선택) |
