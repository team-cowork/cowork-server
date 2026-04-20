# cowork-notification 모듈 설계

## 개요

`cowork-notification` 모듈을 Go + chi 기반으로 새로 구현한다.
`notification.trigger` Kafka 토픽을 구독해 FCM 푸시 알림을 전송하며, 디바이스 토큰을 MySQL로 관리한다.

---

## 기술 스택

| 항목 | 선택 |
|------|------|
| 언어/프레임워크 | Go 1.25 + chi v5 |
| DB | MySQL + GORM |
| Kafka Consumer | segmentio/kafka-go |
| FCM | firebase.google.com/go/v4 |
| 서비스 디스커버리 | go-eureka-client |
| 로깅 | log/slog |

---

## 아키텍처

```
notification.trigger (Kafka)
        │
        ▼
[cowork-notification] (Go + chi)
├─ kafka/consumer.go       ← segmentio/kafka-go
├─ domain/token/           ← 디바이스 토큰 CRUD
├─ infra/fcm/sender.go     ← Firebase Admin SDK
├─ infra/preference/       ← 알림 설정 REST 조회
└─ infra/mysql/            ← GORM + MySQL
        │ FCM
        ▼
[사용자 디바이스]
```

---

## 수신 이벤트 포맷 (notification.trigger)

```json
{
  "type": "CHAT_MESSAGE | MEMBER_INVITED | MEMBER_REMOVED | PROJECT_TASK_ASSIGNED",
  "targetUserIds": [1, 2, 3],
  "data": {}
}
```

- `title`/`body`는 `type` 기준으로 cowork-notification이 자체 생성
- 역직렬화 실패 시 slog 에러 로그 후 offset 커밋하고 스킵

---

## DB 스키마

```sql
CREATE TABLE tb_device_token (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    account_id BIGINT       NOT NULL,
    token      VARCHAR(512) NOT NULL,
    platform   VARCHAR(20)  NOT NULL,  -- ANDROID, IOS, WEB
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tb_device_token_account_token (account_id, token),
    INDEX idx_tb_device_token_account_id (account_id)
);
```

---

## 디렉터리 구조

```
cowork-notification/
├── cmd/server/main.go
├── internal/
│   ├── config/config.go
│   ├── apperr/error.go
│   ├── middleware/auth.go
│   ├── health/handler.go
│   ├── domain/token/
│   │   ├── handler.go
│   │   ├── service.go
│   │   ├── model.go
│   │   ├── ports.go
│   │   └── responses.go
│   └── infra/
│       ├── mysql/token_repository.go
│       ├── kafka/consumer.go
│       ├── fcm/sender.go
│       └── preference/client.go
├── src/main/resources/db/migration/V1__init.sql
├── .env.example
├── Dockerfile
└── go.mod
```

---

## API 엔드포인트

| Method | Path | 설명 |
|--------|------|------|
| POST | `/notifications/tokens` | 디바이스 토큰 등록 (X-User-Id 헤더) |
| DELETE | `/notifications/tokens/{token}` | 디바이스 토큰 삭제 |
| GET | `/health` | 헬스체크 |

---

## 오류 처리

| 상황 | 처리 방식 |
|------|----------|
| FCM 토큰 만료/무효 | tb_device_token에서 자동 삭제 |
| FCM 서버 오류(5xx) | slog 로그 후 스킵, offset 커밋 |
| 역직렬화 실패 | slog 에러 로그 후 스킵, offset 커밋 |
| targetUserIds 중 토큰 없는 사용자 | 스킵, 나머지 정상 발송 |
| cowork-preference 호출 실패 | fail-open (기본값 알림 허용) |

---

## cowork-preference 연동

채널별 알림 설정 확인:
- `GET /accounts/{accountId}/channels/{channelId}/notification`
- 응답 `notification: false`이면 FCM 발송 스킵
- 호출 실패 시 알림 허용(fail-open)으로 처리
