# cowork-notification 모듈 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spring Boot 스켈레톤을 제거하고 Go + chi 기반 FCM 푸시 알림 서비스로 재구현한다.

**Architecture:** `notification.trigger` Kafka 토픽을 소비해 대상 사용자의 디바이스로 FCM 푸시를 전송. 디바이스 토큰은 MySQL에 저장. CHAT_MESSAGE 이벤트에 한해 `cowork-preference`에서 채널별 알림 설정을 확인해 off면 스킵.

**Tech Stack:** Go 1.25, chi v5, GORM + MySQL, segmentio/kafka-go v0.4.47, Firebase Admin SDK Go v4, go-eureka-client v1.1.0

---

## 파일 맵

| 경로 | 작업 | 역할 |
|------|------|------|
| `build.gradle.kts` | **삭제** | Spring Boot 잔여 파일 |
| `src/main/kotlin/...Application.kt` | **삭제** | Spring Boot 잔여 파일 |
| `settings.gradle.kts` (루트) | **수정** | cowork-notification include 제거 |
| `docker/mysql/init.sh` | **수정** | cowork_notification DB 추가 |
| `docker-compose.yml` | **수정** | cowork-notification 서비스 추가 |
| `cowork-notification/go.mod` | **신규** | Go 모듈 정의 |
| `cowork-notification/Dockerfile` | **신규** | 멀티스테이지 빌드 |
| `cowork-notification/.env.example` | **신규** | 환경 변수 템플릿 |
| `cowork-notification/cmd/server/main.go` | **신규** | 진입점 및 와이어링 |
| `cowork-notification/internal/config/config.go` | **신규** | 환경변수 로딩 |
| `cowork-notification/internal/apperr/error.go` | **신규** | 공통 에러 타입 |
| `cowork-notification/internal/middleware/auth.go` | **신규** | X-User-Id 헤더 추출 미들웨어 |
| `cowork-notification/internal/health/handler.go` | **신규** | 헬스체크 핸들러 |
| `cowork-notification/internal/domain/token/model.go` | **신규** | GORM 엔티티 |
| `cowork-notification/internal/domain/token/ports.go` | **신규** | Repository/FCM/Preference 인터페이스 |
| `cowork-notification/internal/domain/token/responses.go` | **신규** | HTTP 응답 구조체 |
| `cowork-notification/internal/domain/token/service.go` | **신규** | 비즈니스 로직 |
| `cowork-notification/internal/domain/token/handler.go` | **신규** | Chi HTTP 핸들러 |
| `cowork-notification/internal/infra/mysql/token_repository.go` | **신규** | GORM Repository 구현 |
| `cowork-notification/internal/infra/fcm/sender.go` | **신규** | Firebase Admin SDK 래퍼 |
| `cowork-notification/internal/infra/preference/client.go` | **신규** | cowork-preference HTTP 클라이언트 |
| `cowork-notification/internal/infra/kafka/consumer.go` | **신규** | Kafka Consumer |
| `cowork-notification/pkg/eureka/client.go` | **신규** | Eureka 등록 및 하트비트 |

---

## Task 1: 스캐폴딩 — Spring Boot 제거 및 Go 프로젝트 초기화

**Files:**
- Delete: `cowork-notification/build.gradle.kts`
- Delete: `cowork-notification/src/main/kotlin/com/cowork/notification/CoworkNotificationApplication.kt`
- Modify: `settings.gradle.kts` (루트)
- Modify: `docker/mysql/init.sh`
- Create: `cowork-notification/go.mod`
- Create: `cowork-notification/Dockerfile`
- Create: `cowork-notification/.env.example`

- [ ] **Step 1: Spring Boot 파일 삭제**

```bash
cd cowork-notification
rm -rf build.gradle.kts src/
```

- [ ] **Step 2: settings.gradle.kts에서 cowork-notification 제거**

파일: `settings.gradle.kts`

`include("cowork-notification")` 줄을 삭제한다. 결과:

```kotlin
rootProject.name = "cowork-server"

// 인프라 모듈 (Spring Boot 확정)
include("cowork-gateway")
include("cowork-config")

// 비즈니스 서비스 모듈 — 스택 확정 후 include 추가
include("cowork-user")
// include("cowork-channel")
// include("cowork-project")
// include("cowork-team")
include("cowork-preference")
include("cowork-team")
```

- [ ] **Step 3: MySQL init 스크립트에 cowork_notification DB 추가**

파일: `docker/mysql/init.sh`

```bash
#!/bin/bash
set -e

mysql -u root -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
    CREATE DATABASE IF NOT EXISTS cowork_authorization DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    CREATE DATABASE IF NOT EXISTS cowork_user DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    CREATE DATABASE IF NOT EXISTS cowork_team DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    CREATE DATABASE IF NOT EXISTS cowork_project DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    CREATE DATABASE IF NOT EXISTS cowork_channel DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    CREATE DATABASE IF NOT EXISTS cowork_notification DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

    CREATE USER IF NOT EXISTS '${MYSQL_USER}'@'%' IDENTIFIED BY '${MYSQL_PASSWORD}';

    GRANT ALL PRIVILEGES ON cowork_authorization.* TO '${MYSQL_USER}'@'%';
    GRANT ALL PRIVILEGES ON cowork_user.* TO '${MYSQL_USER}'@'%';
    GRANT ALL PRIVILEGES ON cowork_team.* TO '${MYSQL_USER}'@'%';
    GRANT ALL PRIVILEGES ON cowork_project.* TO '${MYSQL_USER}'@'%';
    GRANT ALL PRIVILEGES ON cowork_channel.* TO '${MYSQL_USER}'@'%';
    GRANT ALL PRIVILEGES ON cowork_notification.* TO '${MYSQL_USER}'@'%';

    FLUSH PRIVILEGES;
EOSQL
```

- [ ] **Step 4: go.mod 생성**

파일: `cowork-notification/go.mod`

```
module github.com/cowork/cowork-notification

go 1.25.0

require (
	github.com/ArthurHlt/go-eureka-client v1.1.0
	github.com/go-chi/chi/v5 v5.2.1
	firebase.google.com/go/v4 v4.14.1
	github.com/segmentio/kafka-go v0.4.47
	github.com/stretchr/testify v1.10.0
	google.golang.org/api v0.169.0
	gorm.io/driver/mysql v1.6.0
	gorm.io/gorm v1.31.1
)
```

이후 아래를 실행해 `go.sum`을 생성한다:

```bash
cd cowork-notification
go mod tidy
```

- [ ] **Step 5: Dockerfile 생성**

파일: `cowork-notification/Dockerfile`

```dockerfile
FROM golang:1.23-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o cowork-notification ./cmd/server

FROM alpine:3.20 AS runtime
RUN apk --no-cache add ca-certificates tzdata
COPY --from=builder /app/cowork-notification /usr/local/bin/cowork-notification
EXPOSE 8086
CMD ["cowork-notification"]
```

- [ ] **Step 6: .env.example 생성**

파일: `cowork-notification/.env.example`

```env
# Server
PORT=8086

# Database
DB_DSN=cowork:password@tcp(cowork-mysql:3306)/cowork_notification?charset=utf8mb4&parseTime=True&loc=Local

# Kafka
KAFKA_BROKERS=kafka:9092
KAFKA_TOPIC_NOTIFICATION=notification.trigger
KAFKA_GROUP_ID=cowork-notification

# Firebase
FCM_CREDENTIALS_FILE=/etc/secrets/firebase-credentials.json

# Preference Service
PREFERENCE_SERVICE_URL=http://cowork-preference:8084

# Eureka
EUREKA_SERVER_URL=http://cowork-config:8761/eureka
EUREKA_APP_NAME=cowork-notification
EUREKA_INSTANCE_HOST=cowork-notification
EUREKA_INSTANCE_PORT=8086
```

- [ ] **Step 7: 커밋**

```bash
git add cowork-notification/ docker/mysql/init.sh settings.gradle.kts
git commit -m "chore: scaffold cowork-notification as Go module"
```

---

## Task 2: Config, apperr, 도메인 기반 타입

**Files:**
- Create: `cowork-notification/internal/config/config.go`
- Create: `cowork-notification/internal/apperr/error.go`
- Create: `cowork-notification/internal/domain/token/model.go`
- Create: `cowork-notification/internal/domain/token/ports.go`
- Create: `cowork-notification/internal/domain/token/responses.go`

- [ ] **Step 1: config/config.go 작성**

파일: `cowork-notification/internal/config/config.go`

```go
package config

import (
	"fmt"
	"os"
)

type AppConfig struct {
	Port                string
	DBDSN               string
	KafkaBrokers        string
	KafkaTopicNotify    string
	KafkaGroupID        string
	FCMCredentialsFile  string
	PreferenceServiceURL string
	EurekaServerURL     string
	EurekaAppName       string
	EurekaInstanceHost  string
	EurekaInstancePort  int
}

func Load() (*AppConfig, error) {
	dbDSN, err := requireEnv("DB_DSN")
	if err != nil {
		return nil, err
	}
	kafkaBrokers, err := requireEnv("KAFKA_BROKERS")
	if err != nil {
		return nil, err
	}
	kafkaTopic, err := requireEnv("KAFKA_TOPIC_NOTIFICATION")
	if err != nil {
		return nil, err
	}
	kafkaGroup, err := requireEnv("KAFKA_GROUP_ID")
	if err != nil {
		return nil, err
	}
	fcmFile, err := requireEnv("FCM_CREDENTIALS_FILE")
	if err != nil {
		return nil, err
	}
	prefURL, err := requireEnv("PREFERENCE_SERVICE_URL")
	if err != nil {
		return nil, err
	}

	return &AppConfig{
		Port:                 getEnv("PORT", "8086"),
		DBDSN:                dbDSN,
		KafkaBrokers:         kafkaBrokers,
		KafkaTopicNotify:     kafkaTopic,
		KafkaGroupID:         kafkaGroup,
		FCMCredentialsFile:   fcmFile,
		PreferenceServiceURL: prefURL,
		EurekaServerURL:      getEnv("EUREKA_SERVER_URL", "http://localhost:8761/eureka"),
		EurekaAppName:        getEnv("EUREKA_APP_NAME", "cowork-notification"),
		EurekaInstanceHost:   getEnv("EUREKA_INSTANCE_HOST", "localhost"),
		EurekaInstancePort:   8086,
	}, nil
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func requireEnv(key string) (string, error) {
	v := os.Getenv(key)
	if v == "" {
		return "", fmt.Errorf("required environment variable %q is not set", key)
	}
	return v, nil
}
```

- [ ] **Step 2: apperr/error.go 작성**

파일: `cowork-notification/internal/apperr/error.go`

```go
package apperr

import "fmt"

type AppError struct {
	Code    int
	Message string
}

func (e *AppError) Error() string {
	return fmt.Sprintf("code=%d message=%s", e.Code, e.Message)
}

func NotFound(msg string) *AppError  { return &AppError{Code: 404, Message: msg} }
func Internal(msg string) *AppError  { return &AppError{Code: 500, Message: msg} }
func BadRequest(msg string) *AppError { return &AppError{Code: 400, Message: msg} }
```

- [ ] **Step 3: domain/token/model.go 작성**

파일: `cowork-notification/internal/domain/token/model.go`

```go
package token

import "time"

type DeviceToken struct {
	ID        int64     `gorm:"primaryKey;autoIncrement;column:id"`
	AccountID int64     `gorm:"column:account_id;not null;uniqueIndex:uq_tb_device_token_account_token"`
	Token     string    `gorm:"column:token;size:512;not null;uniqueIndex:uq_tb_device_token_account_token"`
	Platform  string    `gorm:"column:platform;size:20;not null"`
	CreatedAt time.Time `gorm:"column:created_at;autoCreateTime"`
	UpdatedAt time.Time `gorm:"column:updated_at;autoUpdateTime"`
}

func (DeviceToken) TableName() string { return "tb_device_token" }
```

- [ ] **Step 4: domain/token/ports.go 작성**

파일: `cowork-notification/internal/domain/token/ports.go`

```go
package token

import "context"

type Repository interface {
	Save(ctx context.Context, t *DeviceToken) error
	FindByAccountID(ctx context.Context, accountID int64) ([]DeviceToken, error)
	DeleteByToken(ctx context.Context, token string) error
}

type FCMSender interface {
	Send(ctx context.Context, tokens []string, title, body string, data map[string]string) (invalidTokens []string, err error)
}

type PreferenceClient interface {
	IsNotificationEnabled(ctx context.Context, accountID, channelID int64) (bool, error)
}
```

- [ ] **Step 5: domain/token/responses.go 작성**

파일: `cowork-notification/internal/domain/token/responses.go`

```go
package token

type RegisterTokenResponse struct {
	AccountID int64  `json:"accountId"`
	Token     string `json:"token"`
	Platform  string `json:"platform"`
}
```

- [ ] **Step 6: 빌드 확인**

```bash
cd cowork-notification
go build ./...
```

Expected: 에러 없음

- [ ] **Step 7: 커밋**

```bash
git add cowork-notification/internal/
git commit -m "feat(notification): add config, apperr, and domain base types"
```

---

## Task 3: MySQL Repository

**Files:**
- Create: `cowork-notification/internal/infra/mysql/token_repository.go`
- Create: `cowork-notification/internal/infra/mysql/token_repository_test.go`

- [ ] **Step 1: 테스트 작성 (인터페이스 준수 확인)**

파일: `cowork-notification/internal/infra/mysql/token_repository_test.go`

```go
package mysql_test

import (
	"testing"

	"github.com/cowork/cowork-notification/internal/domain/token"
	"github.com/cowork/cowork-notification/internal/infra/mysql"
	"github.com/stretchr/testify/assert"
)

func TestTokenRepository_ImplementsInterface(t *testing.T) {
	var _ token.Repository = (*mysql.TokenRepository)(nil)
	assert.True(t, true)
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 에러 확인**

```bash
cd cowork-notification
go test ./internal/infra/mysql/... -v
```

Expected: `mysql.TokenRepository` 미정의 컴파일 에러

- [ ] **Step 3: token_repository.go 구현**

파일: `cowork-notification/internal/infra/mysql/token_repository.go`

```go
package mysql

import (
	"context"

	"github.com/cowork/cowork-notification/internal/domain/token"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

type TokenRepository struct {
	db *gorm.DB
}

func NewTokenRepository(db *gorm.DB) *TokenRepository {
	return &TokenRepository{db: db}
}

func (r *TokenRepository) Save(ctx context.Context, t *token.DeviceToken) error {
	return r.db.WithContext(ctx).
		Clauses(clause.OnConflict{
			Columns:   []clause.Column{{Name: "account_id"}, {Name: "token"}},
			DoUpdates: clause.AssignmentColumns([]string{"platform", "updated_at"}),
		}).
		Create(t).Error
}

func (r *TokenRepository) FindByAccountID(ctx context.Context, accountID int64) ([]token.DeviceToken, error) {
	var tokens []token.DeviceToken
	err := r.db.WithContext(ctx).Where("account_id = ?", accountID).Find(&tokens).Error
	return tokens, err
}

func (r *TokenRepository) DeleteByToken(ctx context.Context, tkn string) error {
	return r.db.WithContext(ctx).Where("token = ?", tkn).Delete(&token.DeviceToken{}).Error
}
```

- [ ] **Step 4: 테스트 재실행 — 통과 확인**

```bash
cd cowork-notification
go test ./internal/infra/mysql/... -v
```

Expected: `PASS`

- [ ] **Step 5: 커밋**

```bash
git add cowork-notification/internal/infra/mysql/
git commit -m "feat(notification): add MySQL token repository"
```

---

## Task 4: FCM Sender

**Files:**
- Create: `cowork-notification/internal/infra/fcm/sender.go`

- [ ] **Step 1: sender.go 구현**

파일: `cowork-notification/internal/infra/fcm/sender.go`

```go
package fcm

import (
	"context"
	"log/slog"

	firebase "firebase.google.com/go/v4"
	"firebase.google.com/go/v4/messaging"
	"google.golang.org/api/option"
)

type Sender struct {
	client *messaging.Client
}

func NewSender(ctx context.Context, credentialsFile string) (*Sender, error) {
	app, err := firebase.NewApp(ctx, nil, option.WithCredentialsFile(credentialsFile))
	if err != nil {
		return nil, err
	}
	client, err := app.Messaging(ctx)
	if err != nil {
		return nil, err
	}
	return &Sender{client: client}, nil
}

func (s *Sender) Send(ctx context.Context, tokens []string, title, body string, data map[string]string) ([]string, error) {
	if len(tokens) == 0 {
		return nil, nil
	}

	msg := &messaging.MulticastMessage{
		Notification: &messaging.Notification{Title: title, Body: body},
		Data:         data,
		Tokens:       tokens,
	}

	resp, err := s.client.SendEachForMulticast(ctx, msg)
	if err != nil {
		return nil, err
	}

	var invalid []string
	for i, r := range resp.Responses {
		if !r.Success {
			if messaging.IsRegistrationTokenNotRegistered(r.Error) || messaging.IsInvalidArgument(r.Error) {
				invalid = append(invalid, tokens[i])
			} else {
				slog.Warn("fcm send failed for token", "err", r.Error, "token", tokens[i])
			}
		}
	}
	return invalid, nil
}
```

- [ ] **Step 2: 빌드 확인**

```bash
cd cowork-notification
go build ./internal/infra/fcm/...
```

Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add cowork-notification/internal/infra/fcm/
git commit -m "feat(notification): add FCM sender"
```

---

## Task 5: Preference HTTP 클라이언트

**Files:**
- Create: `cowork-notification/internal/infra/preference/client.go`
- Create: `cowork-notification/internal/infra/preference/client_test.go`

- [ ] **Step 1: 테스트 작성**

파일: `cowork-notification/internal/infra/preference/client_test.go`

```go
package preference_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/cowork/cowork-notification/internal/infra/preference"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestClient_IsNotificationEnabled_true(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/accounts/1/channels/2/notification", r.URL.Path)
		json.NewEncoder(w).Encode(map[string]bool{"notification": true})
	}))
	defer srv.Close()

	c := preference.NewClient(srv.URL)
	enabled, err := c.IsNotificationEnabled(context.Background(), 1, 2)
	require.NoError(t, err)
	assert.True(t, enabled)
}

func TestClient_IsNotificationEnabled_false(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]bool{"notification": false})
	}))
	defer srv.Close()

	c := preference.NewClient(srv.URL)
	enabled, err := c.IsNotificationEnabled(context.Background(), 1, 2)
	require.NoError(t, err)
	assert.False(t, enabled)
}

func TestClient_IsNotificationEnabled_serverError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	c := preference.NewClient(srv.URL)
	_, err := c.IsNotificationEnabled(context.Background(), 1, 2)
	assert.Error(t, err)
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
cd cowork-notification
go test ./internal/infra/preference/... -v
```

Expected: `preference.NewClient` 미정의 컴파일 에러

- [ ] **Step 3: client.go 구현**

파일: `cowork-notification/internal/infra/preference/client.go`

```go
package preference

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

type Client struct {
	baseURL    string
	httpClient *http.Client
}

func NewClient(baseURL string) *Client {
	return &Client{
		baseURL:    baseURL,
		httpClient: &http.Client{Timeout: 3 * time.Second},
	}
}

func (c *Client) IsNotificationEnabled(ctx context.Context, accountID, channelID int64) (bool, error) {
	url := fmt.Sprintf("%s/accounts/%d/channels/%d/notification", c.baseURL, accountID, channelID)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return true, err
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return true, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return true, fmt.Errorf("preference service returned status %d", resp.StatusCode)
	}

	var body struct {
		Notification bool `json:"notification"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return true, err
	}
	return body.Notification, nil
}
```

- [ ] **Step 4: 테스트 재실행 — 통과 확인**

```bash
cd cowork-notification
go test ./internal/infra/preference/... -v
```

Expected: `PASS` (3개 테스트)

- [ ] **Step 5: 커밋**

```bash
git add cowork-notification/internal/infra/preference/
git commit -m "feat(notification): add preference HTTP client"
```

---

## Task 6: Token Service

**Files:**
- Create: `cowork-notification/internal/domain/token/service.go`
- Create: `cowork-notification/internal/domain/token/service_test.go`

- [ ] **Step 1: 테스트 작성 (mock 포함)**

파일: `cowork-notification/internal/domain/token/service_test.go`

```go
package token_test

import (
	"context"
	"errors"
	"testing"

	"github.com/cowork/cowork-notification/internal/domain/token"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// --- mocks ---

type mockRepo struct {
	saved   *token.DeviceToken
	tokens  map[int64][]token.DeviceToken
	deleted string
	err     error
}

func (m *mockRepo) Save(_ context.Context, t *token.DeviceToken) error {
	m.saved = t
	return m.err
}
func (m *mockRepo) FindByAccountID(_ context.Context, id int64) ([]token.DeviceToken, error) {
	return m.tokens[id], m.err
}
func (m *mockRepo) DeleteByToken(_ context.Context, tkn string) error {
	m.deleted = tkn
	return m.err
}

type mockFCM struct {
	calledTokens []string
	invalid      []string
	err          error
}

func (m *mockFCM) Send(_ context.Context, tokens []string, _, _ string, _ map[string]string) ([]string, error) {
	m.calledTokens = tokens
	return m.invalid, m.err
}

type mockPref struct {
	enabled bool
	err     error
}

func (m *mockPref) IsNotificationEnabled(_ context.Context, _, _ int64) (bool, error) {
	return m.enabled, m.err
}

// --- tests ---

func TestService_RegisterToken(t *testing.T) {
	repo := &mockRepo{}
	svc := token.NewService(repo, &mockFCM{}, &mockPref{enabled: true})

	err := svc.RegisterToken(context.Background(), 1, "token-abc", "ANDROID")
	require.NoError(t, err)
	assert.Equal(t, int64(1), repo.saved.AccountID)
	assert.Equal(t, "token-abc", repo.saved.Token)
	assert.Equal(t, "ANDROID", repo.saved.Platform)
}

func TestService_DeleteToken(t *testing.T) {
	repo := &mockRepo{}
	svc := token.NewService(repo, &mockFCM{}, &mockPref{enabled: true})

	err := svc.DeleteToken(context.Background(), "token-abc")
	require.NoError(t, err)
	assert.Equal(t, "token-abc", repo.deleted)
}

func TestService_Notify_sendsToAllUsers(t *testing.T) {
	repo := &mockRepo{tokens: map[int64][]token.DeviceToken{
		1: {{Token: "t1", AccountID: 1}},
		2: {{Token: "t2", AccountID: 2}},
	}}
	fcm := &mockFCM{}
	svc := token.NewService(repo, fcm, &mockPref{enabled: true})

	err := svc.Notify(context.Background(), []int64{1, 2}, "title", "body", 0)
	require.NoError(t, err)
	assert.ElementsMatch(t, []string{"t1", "t2"}, fcm.calledTokens)
}

func TestService_Notify_skipsDisabledChannel(t *testing.T) {
	repo := &mockRepo{tokens: map[int64][]token.DeviceToken{
		1: {{Token: "t1", AccountID: 1}},
	}}
	fcm := &mockFCM{}
	svc := token.NewService(repo, fcm, &mockPref{enabled: false})

	err := svc.Notify(context.Background(), []int64{1}, "title", "body", 42)
	require.NoError(t, err)
	assert.Nil(t, fcm.calledTokens)
}

func TestService_Notify_deletesInvalidTokens(t *testing.T) {
	repo := &mockRepo{tokens: map[int64][]token.DeviceToken{
		1: {{Token: "bad-token", AccountID: 1}},
	}}
	fcm := &mockFCM{invalid: []string{"bad-token"}}
	svc := token.NewService(repo, fcm, &mockPref{enabled: true})

	err := svc.Notify(context.Background(), []int64{1}, "title", "body", 0)
	require.NoError(t, err)
	assert.Equal(t, "bad-token", repo.deleted)
}

func TestService_Notify_preferenceFailDefaultsToEnabled(t *testing.T) {
	repo := &mockRepo{tokens: map[int64][]token.DeviceToken{
		1: {{Token: "t1", AccountID: 1}},
	}}
	fcm := &mockFCM{}
	pref := &mockPref{err: errors.New("preference service unreachable")}
	svc := token.NewService(repo, fcm, pref)

	err := svc.Notify(context.Background(), []int64{1}, "title", "body", 42)
	require.NoError(t, err)
	assert.Equal(t, []string{"t1"}, fcm.calledTokens)
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
cd cowork-notification
go test ./internal/domain/token/... -v
```

Expected: `token.NewService` 미정의 컴파일 에러

- [ ] **Step 3: service.go 구현**

파일: `cowork-notification/internal/domain/token/service.go`

```go
package token

import (
	"context"
	"log/slog"
)

type Service struct {
	repo Repository
	fcm  FCMSender
	pref PreferenceClient
}

func NewService(repo Repository, fcm FCMSender, pref PreferenceClient) *Service {
	return &Service{repo: repo, fcm: fcm, pref: pref}
}

func (s *Service) RegisterToken(ctx context.Context, accountID int64, tkn, platform string) error {
	return s.repo.Save(ctx, &DeviceToken{
		AccountID: accountID,
		Token:     tkn,
		Platform:  platform,
	})
}

func (s *Service) DeleteToken(ctx context.Context, tkn string) error {
	return s.repo.DeleteByToken(ctx, tkn)
}

func (s *Service) Notify(ctx context.Context, targetUserIDs []int64, title, body string, channelID int64) error {
	var allTokens []string
	for _, uid := range targetUserIDs {
		if channelID > 0 {
			enabled, err := s.pref.IsNotificationEnabled(ctx, uid, channelID)
			if err != nil {
				slog.Warn("preference check failed, defaulting to enabled", "accountId", uid, "err", err)
				enabled = true
			}
			if !enabled {
				continue
			}
		}
		tokens, err := s.repo.FindByAccountID(ctx, uid)
		if err != nil {
			slog.Warn("failed to fetch tokens", "accountId", uid, "err", err)
			continue
		}
		for _, t := range tokens {
			allTokens = append(allTokens, t.Token)
		}
	}

	if len(allTokens) == 0 {
		return nil
	}

	invalidTokens, err := s.fcm.Send(ctx, allTokens, title, body, nil)
	if err != nil {
		return err
	}
	for _, t := range invalidTokens {
		if delErr := s.repo.DeleteByToken(ctx, t); delErr != nil {
			slog.Warn("failed to delete invalid token", "token", t, "err", delErr)
		}
	}
	return nil
}
```

- [ ] **Step 4: 테스트 재실행 — 통과 확인**

```bash
cd cowork-notification
go test ./internal/domain/token/... -v
```

Expected: `PASS` (5개 테스트)

- [ ] **Step 5: 커밋**

```bash
git add cowork-notification/internal/domain/token/
git commit -m "feat(notification): add token service with tests"
```

---

## Task 7: Kafka Consumer

**Files:**
- Create: `cowork-notification/internal/infra/kafka/consumer.go`
- Create: `cowork-notification/internal/infra/kafka/consumer_test.go`

- [ ] **Step 1: 테스트 작성**

파일: `cowork-notification/internal/infra/kafka/consumer_test.go`

```go
package kafka_test

import (
	"context"
	"encoding/json"
	"testing"

	kafkainfra "github.com/cowork/cowork-notification/internal/infra/kafka"
	segkafka "github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type mockNotificationService struct {
	calledUserIDs []int64
	calledTitle   string
	calledBody    string
	calledChannel int64
	err           error
}

func (m *mockNotificationService) Notify(_ context.Context, ids []int64, title, body string, channelID int64) error {
	m.calledUserIDs = ids
	m.calledTitle = title
	m.calledBody = body
	m.calledChannel = channelID
	return m.err
}

func TestConsumer_handle_chatMessage(t *testing.T) {
	svc := &mockNotificationService{}
	c := kafkainfra.NewConsumerForTest(svc)

	event := kafkainfra.NotificationTriggerEvent{
		Type:          "CHAT_MESSAGE",
		TargetUserIDs: []int64{1, 2},
		Data:          map[string]interface{}{"channelId": float64(42)},
	}
	raw, err := json.Marshal(event)
	require.NoError(t, err)

	c.HandleForTest(context.Background(), segkafka.Message{Value: raw})

	assert.Equal(t, []int64{1, 2}, svc.calledUserIDs)
	assert.Equal(t, "새 메시지", svc.calledTitle)
	assert.Equal(t, int64(42), svc.calledChannel)
}

func TestConsumer_handle_memberInvited(t *testing.T) {
	svc := &mockNotificationService{}
	c := kafkainfra.NewConsumerForTest(svc)

	event := kafkainfra.NotificationTriggerEvent{
		Type:          "MEMBER_INVITED",
		TargetUserIDs: []int64{5},
	}
	raw, _ := json.Marshal(event)
	c.HandleForTest(context.Background(), segkafka.Message{Value: raw})

	assert.Equal(t, "팀 초대", svc.calledTitle)
	assert.Equal(t, int64(0), svc.calledChannel)
}

func TestConsumer_handle_invalidJSON(t *testing.T) {
	svc := &mockNotificationService{}
	c := kafkainfra.NewConsumerForTest(svc)

	c.HandleForTest(context.Background(), segkafka.Message{Value: []byte("not-json")})

	assert.Nil(t, svc.calledUserIDs)
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
cd cowork-notification
go test ./internal/infra/kafka/... -v
```

Expected: `kafkainfra.NewConsumerForTest` 미정의 컴파일 에러

- [ ] **Step 3: consumer.go 구현**

파일: `cowork-notification/internal/infra/kafka/consumer.go`

```go
package kafka

import (
	"context"
	"encoding/json"
	"log/slog"
	"strings"

	segkafka "github.com/segmentio/kafka-go"
)

type NotificationTriggerEvent struct {
	Type          string                 `json:"type"`
	TargetUserIDs []int64                `json:"targetUserIds"`
	Data          map[string]interface{} `json:"data"`
}

type NotificationService interface {
	Notify(ctx context.Context, targetUserIDs []int64, title, body string, channelID int64) error
}

type Consumer struct {
	reader *segkafka.Reader
	svc    NotificationService
}

func NewConsumer(brokers, topic, groupID string, svc NotificationService) *Consumer {
	return &Consumer{
		reader: segkafka.NewReader(segkafka.ReaderConfig{
			Brokers: strings.Split(brokers, ","),
			Topic:   topic,
			GroupID: groupID,
		}),
		svc: svc,
	}
}

// NewConsumerForTest returns a Consumer with no Kafka reader — for unit tests only.
func NewConsumerForTest(svc NotificationService) *Consumer {
	return &Consumer{svc: svc}
}

// HandleForTest exposes handle for unit testing.
func (c *Consumer) HandleForTest(ctx context.Context, msg segkafka.Message) {
	c.handle(ctx, msg)
}

func (c *Consumer) Start(ctx context.Context) {
	for {
		msg, err := c.reader.ReadMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			slog.Error("kafka read error", "err", err)
			continue
		}
		c.handle(ctx, msg)
	}
}

func (c *Consumer) Close() error {
	if c.reader == nil {
		return nil
	}
	return c.reader.Close()
}

func (c *Consumer) handle(ctx context.Context, msg segkafka.Message) {
	var event NotificationTriggerEvent
	if err := json.Unmarshal(msg.Value, &event); err != nil {
		slog.Error("failed to unmarshal notification event", "err", err, "offset", msg.Offset)
		return
	}
	title, body := buildMessage(event.Type)
	channelID := extractChannelID(event.Data)
	if err := c.svc.Notify(ctx, event.TargetUserIDs, title, body, channelID); err != nil {
		slog.Error("notification failed", "err", err, "type", event.Type)
	}
}

func buildMessage(eventType string) (title, body string) {
	switch eventType {
	case "CHAT_MESSAGE":
		return "새 메시지", "새 메시지가 도착했습니다."
	case "MEMBER_INVITED":
		return "팀 초대", "팀에 초대되었습니다."
	case "MEMBER_REMOVED":
		return "팀 멤버 제거", "팀에서 제거되었습니다."
	case "PROJECT_TASK_ASSIGNED":
		return "태스크 할당", "새 태스크가 할당되었습니다."
	default:
		return "알림", ""
	}
}

func extractChannelID(data map[string]interface{}) int64 {
	if data == nil {
		return 0
	}
	v, ok := data["channelId"]
	if !ok {
		return 0
	}
	switch n := v.(type) {
	case float64:
		return int64(n)
	case int64:
		return n
	}
	return 0
}
```

- [ ] **Step 4: 테스트 재실행 — 통과 확인**

```bash
cd cowork-notification
go test ./internal/infra/kafka/... -v
```

Expected: `PASS` (3개 테스트)

- [ ] **Step 5: 커밋**

```bash
git add cowork-notification/internal/infra/kafka/
git commit -m "feat(notification): add Kafka consumer with tests"
```

---

## Task 8: HTTP 핸들러 및 미들웨어

**Files:**
- Create: `cowork-notification/internal/middleware/auth.go`
- Create: `cowork-notification/internal/health/handler.go`
- Create: `cowork-notification/internal/domain/token/handler.go`
- Create: `cowork-notification/internal/domain/token/handler_test.go`

- [ ] **Step 1: middleware/auth.go 작성**

파일: `cowork-notification/internal/middleware/auth.go`

```go
package middleware

import (
	"context"
	"net/http"
	"strconv"
)

type contextKey string

const contextKeyAccountID contextKey = "accountID"

func ExtractAuthUser(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		userIDStr := r.Header.Get("X-User-Id")
		if userIDStr == "" {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		userID, err := strconv.ParseInt(userIDStr, 10, 64)
		if err != nil {
			http.Error(w, "invalid user id", http.StatusUnauthorized)
			return
		}
		ctx := context.WithValue(r.Context(), contextKeyAccountID, userID)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func AccountIDFromContext(ctx context.Context) (int64, bool) {
	id, ok := ctx.Value(contextKeyAccountID).(int64)
	return id, ok
}

func WithAccountID(ctx context.Context, id int64) context.Context {
	return context.WithValue(ctx, contextKeyAccountID, id)
}
```

- [ ] **Step 2: health/handler.go 작성**

파일: `cowork-notification/internal/health/handler.go`

```go
package health

import "net/http"

func Handler(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK)
	w.Write([]byte("ok"))
}
```

- [ ] **Step 3: 핸들러 테스트 작성**

파일: `cowork-notification/internal/domain/token/handler_test.go`

```go
package token_test

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/cowork/cowork-notification/internal/domain/token"
	"github.com/cowork/cowork-notification/internal/middleware"
	"github.com/go-chi/chi/v5"
	"github.com/stretchr/testify/assert"
)

type mockService struct {
	registerErr error
	deleteErr   error
}

func (m *mockService) RegisterToken(_ context.Context, _ int64, _, _ string) error {
	return m.registerErr
}
func (m *mockService) DeleteToken(_ context.Context, _ string) error {
	return m.deleteErr
}
func (m *mockService) Notify(_ context.Context, _ []int64, _, _ string, _ int64) error {
	return nil
}

func TestHandler_RegisterToken_success(t *testing.T) {
	svc := &mockService{}
	h := token.NewHandler(svc)

	body := `{"token":"fcm-token-123","platform":"ANDROID"}`
	r := httptest.NewRequest(http.MethodPost, "/notifications/tokens", strings.NewReader(body))
	r = r.WithContext(middleware.WithAccountID(r.Context(), int64(1)))
	w := httptest.NewRecorder()

	h.RegisterToken(w, r)

	assert.Equal(t, http.StatusCreated, w.Code)
}

func TestHandler_RegisterToken_missingBody(t *testing.T) {
	h := token.NewHandler(&mockService{})

	r := httptest.NewRequest(http.MethodPost, "/notifications/tokens", strings.NewReader(`{}`))
	r = r.WithContext(middleware.WithAccountID(r.Context(), int64(1)))
	w := httptest.NewRecorder()

	h.RegisterToken(w, r)

	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestHandler_RegisterToken_serviceError(t *testing.T) {
	svc := &mockService{registerErr: errors.New("db error")}
	h := token.NewHandler(svc)

	body := `{"token":"t","platform":"IOS"}`
	r := httptest.NewRequest(http.MethodPost, "/notifications/tokens", strings.NewReader(body))
	r = r.WithContext(middleware.WithAccountID(r.Context(), int64(1)))
	w := httptest.NewRecorder()

	h.RegisterToken(w, r)

	assert.Equal(t, http.StatusInternalServerError, w.Code)
}

func TestHandler_DeleteToken_success(t *testing.T) {
	h := token.NewHandler(&mockService{})

	r := httptest.NewRequest(http.MethodDelete, "/notifications/tokens/my-token", nil)
	rctx := chi.NewRouteContext()
	rctx.URLParams.Add("token", "my-token")
	r = r.WithContext(context.WithValue(r.Context(), chi.RouteCtxKey, rctx))
	w := httptest.NewRecorder()

	h.DeleteToken(w, r)

	assert.Equal(t, http.StatusNoContent, w.Code)
}
```

- [ ] **Step 4: 테스트 실행 — 실패 확인**

```bash
cd cowork-notification
go test ./internal/domain/token/... -run TestHandler -v
```

Expected: `token.NewHandler` 미정의 컴파일 에러

- [ ] **Step 5: handler.go 구현**

`handler.go`에서 service는 인터페이스로 받는다. `ports.go`에 `TokenService` 인터페이스를 추가한다.

파일: `cowork-notification/internal/domain/token/ports.go` (추가)

```go
package token

import "context"

type Repository interface {
	Save(ctx context.Context, t *DeviceToken) error
	FindByAccountID(ctx context.Context, accountID int64) ([]DeviceToken, error)
	DeleteByToken(ctx context.Context, token string) error
}

type FCMSender interface {
	Send(ctx context.Context, tokens []string, title, body string, data map[string]string) (invalidTokens []string, err error)
}

type PreferenceClient interface {
	IsNotificationEnabled(ctx context.Context, accountID, channelID int64) (bool, error)
}

type TokenService interface {
	RegisterToken(ctx context.Context, accountID int64, token, platform string) error
	DeleteToken(ctx context.Context, token string) error
	Notify(ctx context.Context, targetUserIDs []int64, title, body string, channelID int64) error
}
```

파일: `cowork-notification/internal/domain/token/handler.go`

```go
package token

import (
	"encoding/json"
	"net/http"

	"github.com/cowork/cowork-notification/internal/middleware"
	"github.com/go-chi/chi/v5"
)

type Handler struct {
	svc TokenService
}

func NewHandler(svc TokenService) *Handler {
	return &Handler{svc: svc}
}

type registerTokenRequest struct {
	Token    string `json:"token"`
	Platform string `json:"platform"`
}

func (h *Handler) RegisterToken(w http.ResponseWriter, r *http.Request) {
	accountID, ok := middleware.AccountIDFromContext(r.Context())
	if !ok {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	var req registerTokenRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if req.Token == "" || req.Platform == "" {
		http.Error(w, "token and platform are required", http.StatusBadRequest)
		return
	}
	if err := h.svc.RegisterToken(r.Context(), accountID, req.Token, req.Platform); err != nil {
		http.Error(w, "internal server error", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusCreated)
}

func (h *Handler) DeleteToken(w http.ResponseWriter, r *http.Request) {
	tkn := chi.URLParam(r, "token")
	if tkn == "" {
		http.Error(w, "token is required", http.StatusBadRequest)
		return
	}
	if err := h.svc.DeleteToken(r.Context(), tkn); err != nil {
		http.Error(w, "internal server error", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
```

- [ ] **Step 6: 테스트 재실행 — 통과 확인**

```bash
cd cowork-notification
go test ./internal/domain/token/... -v
go test ./internal/middleware/... -v
```

Expected: 모두 `PASS`

- [ ] **Step 7: 커밋**

```bash
git add cowork-notification/internal/middleware/ cowork-notification/internal/health/ cowork-notification/internal/domain/token/
git commit -m "feat(notification): add HTTP handlers and middleware"
```

---

## Task 9: Eureka 클라이언트, main.go 와이어링, docker-compose 업데이트

**Files:**
- Create: `cowork-notification/pkg/eureka/client.go`
- Create: `cowork-notification/cmd/server/main.go`
- Modify: `docker-compose.yml`

- [ ] **Step 1: pkg/eureka/client.go 작성**

파일: `cowork-notification/pkg/eureka/client.go`

```go
package eureka

import (
	"log/slog"
	"time"

	eurekaclient "github.com/ArthurHlt/go-eureka-client/eureka"

	"github.com/cowork/cowork-notification/internal/config"
)

type Client struct {
	inner *eurekaclient.Client
}

func New(cfg *config.AppConfig) *Client {
	return &Client{
		inner: eurekaclient.NewClient([]string{cfg.EurekaServerURL}),
	}
}

func (c *Client) Register(cfg *config.AppConfig) error {
	instance := eurekaclient.NewInstanceInfo(
		cfg.EurekaInstanceHost,
		cfg.EurekaAppName,
		cfg.EurekaInstanceHost,
		cfg.EurekaInstancePort,
		30,
		false,
	)
	instance.Metadata = &eurekaclient.MetaData{
		Map: map[string]string{"startup": time.Now().String()},
	}
	return c.inner.RegisterInstance(cfg.EurekaAppName, instance)
}

func (c *Client) StartHeartbeat(cfg *config.AppConfig) {
	ticker := time.NewTicker(30 * time.Second)
	go func() {
		for range ticker.C {
			if err := c.inner.SendHeartbeat(cfg.EurekaAppName, cfg.EurekaInstanceHost); err != nil {
				slog.Warn("eureka heartbeat failed", "err", err)
			}
		}
	}()
}

func (c *Client) Deregister(cfg *config.AppConfig) error {
	return c.inner.UnregisterInstance(cfg.EurekaAppName, cfg.EurekaInstanceHost)
}
```

- [ ] **Step 2: cmd/server/main.go 작성**

파일: `cowork-notification/cmd/server/main.go`

```go
package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/go-chi/chi/v5"
	chimiddleware "github.com/go-chi/chi/v5/middleware"
	"gorm.io/driver/mysql"
	"gorm.io/gorm"

	"github.com/cowork/cowork-notification/internal/config"
	tokendomain "github.com/cowork/cowork-notification/internal/domain/token"
	"github.com/cowork/cowork-notification/internal/health"
	"github.com/cowork/cowork-notification/internal/infra/fcm"
	kafkainfra "github.com/cowork/cowork-notification/internal/infra/kafka"
	mysqlinfra "github.com/cowork/cowork-notification/internal/infra/mysql"
	"github.com/cowork/cowork-notification/internal/infra/preference"
	"github.com/cowork/cowork-notification/internal/middleware"
	"github.com/cowork/cowork-notification/pkg/eureka"
)

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))

	cfg, err := config.Load()
	if err != nil {
		slog.Error("config load failed", "err", err)
		os.Exit(1)
	}

	db, err := gorm.Open(mysql.Open(cfg.DBDSN), &gorm.Config{})
	if err != nil {
		slog.Error("mysql connect failed", "err", err)
		os.Exit(1)
	}
	if err := db.AutoMigrate(&tokendomain.DeviceToken{}); err != nil {
		slog.Error("auto migrate failed", "err", err)
		os.Exit(1)
	}

	ctx := context.Background()
	fcmSender, err := fcm.NewSender(ctx, cfg.FCMCredentialsFile)
	if err != nil {
		slog.Error("fcm init failed", "err", err)
		os.Exit(1)
	}

	repo := mysqlinfra.NewTokenRepository(db)
	prefClient := preference.NewClient(cfg.PreferenceServiceURL)
	svc := tokendomain.NewService(repo, fcmSender, prefClient)
	handler := tokendomain.NewHandler(svc)

	consumer := kafkainfra.NewConsumer(cfg.KafkaBrokers, cfg.KafkaTopicNotify, cfg.KafkaGroupID, svc)

	eurekaClient := eureka.New(cfg)
	if err := eurekaClient.Register(cfg); err != nil {
		slog.Warn("eureka registration failed", "err", err)
	} else {
		eurekaClient.StartHeartbeat(cfg)
	}

	r := chi.NewRouter()
	r.Use(chimiddleware.RequestID)
	r.Use(chimiddleware.Recoverer)
	r.Get("/health", health.Handler)

	r.Group(func(r chi.Router) {
		r.Use(middleware.ExtractAuthUser)
		r.Post("/notifications/tokens", handler.RegisterToken)
		r.Delete("/notifications/tokens/{token}", handler.DeleteToken)
	})

	srv := &http.Server{
		Addr:    ":" + cfg.Port,
		Handler: r,
	}

	done := make(chan os.Signal, 1)
	serverErrCh := make(chan error, 1)
	signal.Notify(done, syscall.SIGINT, syscall.SIGTERM)
	exitCode := 0

	consumerCtx, consumerCancel := context.WithCancel(context.Background())
	go func() {
		slog.Info("kafka consumer starting")
		consumer.Start(consumerCtx)
	}()

	go func() {
		slog.Info("cowork-notification starting", "port", cfg.Port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			serverErrCh <- err
		}
	}()

	select {
	case sig := <-done:
		slog.Info("shutting down", "signal", sig.String())
	case err := <-serverErrCh:
		slog.Error("server error", "err", err)
		exitCode = 1
	}

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()

	consumerCancel()
	if err := consumer.Close(); err != nil {
		slog.Error("kafka consumer close error", "err", err)
	}
	if err := srv.Shutdown(shutdownCtx); err != nil {
		slog.Error("server shutdown error", "err", err)
	}
	if err := eurekaClient.Deregister(cfg); err != nil {
		slog.Warn("eureka deregister failed", "err", err)
	}

	slog.Info("shutdown complete")
	if exitCode != 0 {
		os.Exit(exitCode)
	}
}
```

- [ ] **Step 3: go mod tidy 실행**

```bash
cd cowork-notification
go mod tidy
```

Expected: `go.sum` 업데이트, 에러 없음

- [ ] **Step 4: 빌드 확인**

```bash
cd cowork-notification
go build ./...
```

Expected: 에러 없음

- [ ] **Step 5: docker-compose.yml에 cowork-notification 서비스 추가**

파일: `docker-compose.yml` — `volumes:` 섹션 바로 위에 추가:

```yaml
  cowork-notification:
    build:
      context: ./cowork-notification
      dockerfile: Dockerfile
    container_name: cowork-notification
    restart: unless-stopped
    environment:
      PORT: 8086
      DB_DSN: ${MYSQL_USER}:${MYSQL_PASSWORD}@tcp(cowork-mysql:3306)/cowork_notification?charset=utf8mb4&parseTime=True&loc=Local
      KAFKA_BROKERS: kafka:9092
      KAFKA_TOPIC_NOTIFICATION: notification.trigger
      KAFKA_GROUP_ID: cowork-notification
      FCM_CREDENTIALS_FILE: /etc/secrets/firebase-credentials.json
      PREFERENCE_SERVICE_URL: http://cowork-preference:8084
      EUREKA_SERVER_URL: http://cowork-config:8761/eureka
      EUREKA_APP_NAME: cowork-notification
      EUREKA_INSTANCE_HOST: cowork-notification
      EUREKA_INSTANCE_PORT: 8086
    ports:
      - "8086:8086"
    depends_on:
      mysql:
        condition: service_healthy
      kafka:
        condition: service_healthy
    healthcheck:
      test: [ "CMD-SHELL", "wget -qO- http://localhost:8086/health || exit 1" ]
      interval: 10s
      timeout: 5s
      retries: 5
```

- [ ] **Step 6: 전체 테스트 실행**

```bash
cd cowork-notification
go test ./... -v
```

Expected: 모두 `PASS`

- [ ] **Step 7: 최종 커밋**

```bash
git add cowork-notification/ docker-compose.yml docker/mysql/init.sh
git commit -m "feat(notification): complete cowork-notification Go implementation"
```

---

## 검증 방법

1. **빌드 확인**
   ```bash
   cd cowork-notification && go build ./...
   ```

2. **전체 테스트**
   ```bash
   cd cowork-notification && go test ./... -v
   ```

3. **Docker Compose 기동**
   ```bash
   docker compose up mysql kafka -d
   ```

4. **서비스 기동** (FCM 크리덴셜 파일 필요)
   ```bash
   cd cowork-notification
   export DB_DSN="cowork:password@tcp(localhost:3306)/cowork_notification?charset=utf8mb4&parseTime=True&loc=Local"
   export KAFKA_BROKERS="localhost:9094"
   export KAFKA_TOPIC_NOTIFICATION="notification.trigger"
   export KAFKA_GROUP_ID="cowork-notification"
   export FCM_CREDENTIALS_FILE="./firebase-credentials.json"
   export PREFERENCE_SERVICE_URL="http://localhost:8084"
   go run ./cmd/server
   ```

5. **토큰 등록 확인**
   ```bash
   curl -X POST http://localhost:8086/notifications/tokens \
     -H "X-User-Id: 1" \
     -H "Content-Type: application/json" \
     -d '{"token":"test-fcm-token","platform":"ANDROID"}'
   # Expected: 201 Created
   ```

6. **Kafka 이벤트 발행 테스트**
   ```bash
   # Kafka UI (http://localhost:8090) 에서 notification.trigger 토픽에 아래 메시지 발행
   {
     "type": "CHAT_MESSAGE",
     "targetUserIds": [1],
     "data": {"channelId": 1}
   }
   # Expected: FCM 발송 로그 확인
   ```

7. **헬스체크**
   ```bash
   curl http://localhost:8086/health
   # Expected: ok
   ```
