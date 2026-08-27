# cowork-notification

## 역할

여러 서비스가 발행한 알림 요청을 사용자 설정에 맞춰 전달하는 서비스입니다.

- Kafka `notification.trigger` 이벤트 소비
- Kafka projection으로 동기화한 채널 알림 설정·사용자 표시명·팀 이름으로 수신 대상과 표시 정보 결정
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
- Liveness / readiness: `/health`, `/health/ready`
- Prometheus: `/metrics`
- Swagger UI: `/swagger/index.html`

API 요청의 사용자는 Gateway가 전달한 `X-User-Id` 헤더로 식별합니다. SSE 라우트는 Gateway에서 응답 timeout을 비활성화합니다.

## 의존성

- MySQL: 디바이스 토큰, 알림 설정·사용자·팀 projection, projection checkpoint/dead letter 저장
- Kafka consume: `notification.trigger`, `preference.channel-notification.changed`, `user.profile.event`, `team.lifecycle`
- Firebase FCM, Eureka, Config Server

## 환경 변수

| 공급원 | 설정 |
|---|---|
| Compose | `APP_CONFIG_URL`, `APP_PROFILE`, Firebase JSON Docker secret mount |
| Config Server | 포트, Kafka topic/group, Eureka, FCM 파일 경로 |
| Vault | `db.dsn` |

`APP_CONFIG_URL`을 지정한 상태에서 Config Server 조회에 실패하면 기동하지 않습니다. Firebase 서비스 계정 JSON은 문자열 환경변수가 아니라 `/run/secrets/firebase-credentials.json`에 read-only로 마운트됩니다.

세 projection topic은 broker consumer-group offset이 아니라 MySQL의 공유
`(consumer_group, topic, partition, next_offset)` checkpoint에서 재생합니다. projection 변경과
checkpoint 갱신은 같은 transaction에서 commit됩니다. Kafka assignment 시 세 topic 전체 partition의
end offset을 고정하며, 모든 공유 checkpoint가 barrier를 통과하기 전에는
`notification.trigger`를 fetch하지 않고 `/health/ready`를 503으로 유지하며 Eureka에도 등록하지
않습니다. 잘못된 key/JSON/계약은 dead-letter table과 checkpoint를 같은 transaction으로 기록하고,
일시적인 DB 오류는 checkpoint를 진전시키지 않은 채 무한 재시도합니다.
저장된 checkpoint가 Kafka의 현재 earliest보다 작거나 end보다 크면 보존 이력 손실 또는 topic
재생성으로 간주해 자동 이동하지 않고 readiness를 닫습니다. 이 경우 projection과 checkpoint를 함께
재구축해야 합니다.
