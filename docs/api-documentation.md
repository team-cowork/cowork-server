# API 문서화 가이드

cowork-server는 Spring Cloud Gateway에서 전 서비스의 OpenAPI 스펙을 **단일 Swagger UI**로 집계합니다.

---

## 전체 구조

```
브라우저
  └─ http://localhost:8080/swagger-ui.html
          │
          ├─ /v3/api-docs/authorization  →  cowork-authorization  /swagger/doc.json  (swaggo)
          ├─ /v3/api-docs/user           →  cowork-user           /v3/api-docs       (springdoc)
          ├─ /v3/api-docs/voice          →  cowork-voice          /swagger/doc.json  (swaggo)
          └─ /v3/api-docs/chat           →  cowork-chat           /api-json          (@nestjs/swagger)
```

Gateway는 각 서비스의 API 스펙을 프록시로 제공하고, Swagger UI의 드롭다운으로 서비스를 전환할 수 있습니다.

---

## 1. 사전 준비 — Go 서비스 문서 생성

Go 서비스(`cowork-authorization`, `cowork-voice`)는 **`swag init`으로 `docs/` 패키지를 생성**해야 빌드가 가능합니다.  
최초 1회, 또는 API 어노테이션을 수정할 때마다 실행합니다.

```bash
# swag CLI 및 의존성 설치 + docs/ 생성 (최초 1회)
cd cowork-authorization && make setup
cd cowork-voice && make setup
```

`make setup` 실행 순서:

| 단계 | 명령 | 설명 |
|------|------|------|
| 1 | `go install swag@latest` | `~/go/bin/swag` CLI 설치 |
| 2 | `go get swaggo/...` | swaggo 패키지 다운로드 및 `go.sum` 갱신 |
| 3 | `swag init` | 어노테이션을 파싱하여 `docs/` 생성 |
| 4 | `go mod tidy` | 불필요한 의존성 정리 |

이후 API 어노테이션을 변경했을 때는 `make swagger-gen`만 다시 실행하면 됩니다.

```bash
make swagger-gen   # docs/ 재생성만
```

---

## 2. NestJS 서비스 의존성 설치

```bash
cd cowork-chat && npm install
```

---

## 3. 서비스 기동 순서

```
[1] docker-compose up -d          # MySQL, Redis, Kafka, Vault, MongoDB
[2] cowork-config  (포트 8761)    # Config Server + Eureka (가장 먼저)
[3] cowork-user    (포트 8082)    # Spring Boot
[4] cowork-authorization          # Go / Gin
[5] cowork-voice                  # Go / Chi
[6] cowork-chat    (포트 3000)    # NestJS
[7] cowork-gateway (포트 8080)    # 마지막
```

---

## 4. Swagger UI 접속 — 통합 뷰

게이트웨이가 실행된 후 브라우저에서 접속합니다.

```
http://localhost:8080/swagger-ui.html
```

상단 드롭다운 (`Select a definition`)에서 서비스를 선택합니다.

| 항목 | 서비스 |
|------|--------|
| `user` (기본 선택) | cowork-user |
| `authorization` | cowork-authorization |
| `voice` | cowork-voice |
| `chat` | cowork-chat |

> **인증 없이** 접근 가능합니다. Gateway Security 설정에서 `/swagger-ui/**`, `/v3/api-docs/**` 경로를 permitAll 처리했습니다.

---

## 5. 서비스별 직접 접속

게이트웨이 없이 각 서비스 Swagger UI에 직접 접근할 수 있습니다.

### cowork-user (Spring Boot)

```
http://localhost:8082/swagger-ui.html    # UI
http://localhost:8082/v3/api-docs        # OpenAPI JSON
```

### cowork-authorization (Go / Gin)

```
http://localhost:{PORT}/swagger/index.html  # UI
http://localhost:{PORT}/swagger/doc.json    # OpenAPI JSON
```

### cowork-voice (Go / Chi)

```
http://localhost:{PORT}/swagger/index.html  # UI
http://localhost:{PORT}/swagger/doc.json    # OpenAPI JSON
```

### cowork-chat (NestJS)

```
http://localhost:3000/api        # Swagger UI (HTTP stub + WebSocket 설명)
http://localhost:3000/api-json   # OpenAPI JSON
```

---

## 6. WebSocket 문서 — cowork-chat

`cowork-chat`은 HTTP REST 엔드포인트 없이 **Socket.io WebSocket**만 사용합니다.  
Swagger UI(`/api`)에는 WebSocket 이벤트 설명이 마크다운 형식으로 표시됩니다.

정식 WebSocket 이벤트 명세는 **AsyncAPI 2.6 포맷**으로 제공됩니다.

```
http://localhost:3000/asyncapi.json
```

이 JSON을 [AsyncAPI Studio](https://studio.asyncapi.com)에 붙여넣으면 시각적으로 확인할 수 있습니다.

### Socket.io 이벤트 요약

| 방향 | 이벤트 | 페이로드 | 설명 |
|------|--------|---------|------|
| Client → Server | `message` | `MessagePayload` | 채널에 메시지 전송 |
| Server → Client | `message` | `MessagePayload` | 채널 멤버 전체 브로드캐스트 |

**MessagePayload**

```json
{
  "channelId": "42",
  "content":   "안녕하세요!"
}
```

---

## 7. Try it out 사용 시 주의사항

Swagger UI의 **Try it out** 기능으로 실제 API를 호출할 때 주의할 점입니다.

### 인증이 필요한 엔드포인트

Gateway를 통해 호출할 경우 JWT Bearer 토큰이 필요합니다.  
Swagger UI 우측 상단 **Authorize** 버튼 → `BearerAuth` → `Bearer <access_token>` 입력.

> `X-User-Id`, `X-User-Role` 헤더는 Gateway가 JWT에서 자동으로 주입하므로 직접 입력하지 않아도 됩니다. (`@Parameter(hidden = true)` 처리됨)

### 서비스 직접 호출 vs Gateway 경유

| 방법 | URL 예시 | 인증 |
|------|---------|------|
| Gateway 경유 | `http://localhost:8080/api/users/me` | JWT 필요 |
| 서비스 직접 | `http://localhost:8082/users/me` | `X-User-Id` 헤더 수동 입력 |

서비스별 Swagger UI에서 직접 호출할 경우 `X-User-Id` 헤더를 수동으로 입력해야 합니다.

---

## 8. API 어노테이션 수정 방법

### Spring Boot (cowork-user)

`UserController.kt`의 `@Operation`, DTO의 `@Schema` 수정 후 서비스를 재기동하면 자동 반영됩니다.

### Go 서비스 (cowork-authorization, cowork-voice)

핸들러 파일의 주석 어노테이션 (`// @Summary`, `// @Param` 등) 수정 후:

```bash
make swagger-gen   # docs/ 재생성
go build ./...     # 빌드 확인
```

swaggo 어노테이션 전체 문법은 [swaggo/swag 공식 문서](https://github.com/swaggo/swag#declarative-comments-format)를 참고하세요.

### NestJS (cowork-chat)

`@ApiProperty()`, `@ApiOperation()` 등 수정 후 서비스를 재기동하면 자동 반영됩니다.  
WebSocket 이벤트가 변경되면 `public/asyncapi.json`도 함께 수정합니다.
