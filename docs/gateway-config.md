# cowork-config와 cowork-gateway 구성

이 문서는 2026-07-23의 `application.yml`, Config Server 설정, Gateway 보안·필터 코드를 기준으로 한다. 로컬 Compose의 기본 프로파일은 `local`이다.

## cowork-config

`cowork-config`는 포트 `8761`에서 Config Server, Eureka Server, Spring Cloud Bus를 함께 제공한다.

### 설정 백엔드

| 프로파일 | 설정 백엔드 우선순위          | 용도                             |
|----------|-------------------------------|----------------------------------|
| `local`  | Vault → `classpath:/configs/` | 로컬 Compose 기본값              |
| `dev`    | Vault → `classpath:/configs/` | 개발 배포                        |
| `native` | `classpath:/configs/`         | Vault 없이 일반 설정만 확인할 때 |
| `prod`   | Vault → Git 저장소            | 운영 배포                        |

Vault는 KV v2의 `secret/application` 공통 경로와 `secret/{application}` 서비스 경로를 읽는다. `prod`의 Git 백엔드는 `CONFIG_GIT_URI`, `CONFIG_GIT_USERNAME`, `CONFIG_GIT_PASSWORD`로 설정한다.

### 로컬 Vault 초기화

`docker-compose.yml`의 일회성 `vault-init` 서비스가 `cowork-config/src/main/resources/vault/vault-init.sh`를 read-only로 마운트해 시크릿을 기록한다.

```bash
docker compose up vault-init
```

현재 시드하는 경로는 다음과 같다.

| Vault 경로                    | 주요 값                                  |
|-------------------------------|------------------------------------------|
| `secret/application`          | JWT, MySQL/PostgreSQL, MinIO credential  |
| `secret/cowork-gateway`       | JWT 검증 키                              |
| `secret/cowork-authorization` | DB DSN, DataGSM, JWT                     |
| `secret/cowork-channel`       | AccountShare 암호화·OAuth 값             |
| `secret/cowork-chat`          | MongoDB, Discord webhook                 |
| `secret/cowork-notification`  | DB DSN                                   |
| `secret/cowork-preference`    | PostgreSQL 계정                          |
| `secret/cowork-project`       | GitHub App 내부 키                       |
| `secret/cowork-user`          | MySQL 계정, Phoenix `SECRET_KEY_BASE`    |
| `secret/cowork-voice`         | MongoDB, LiveKit                         |

Vault dev 서버는 메모리 기반이다. Docker Desktop이나 Vault 컨테이너를 재시작해 데이터가 사라졌다면 `vault-init`을 다시 실행하고 Vault 값을 시작 시 읽는 서비스를 재시작한다.

### Eureka와 Config Bus

`cowork-config` 자체는 Eureka에 등록하지 않으며, self-preservation을 끄고 5초 간격으로 만료 인스턴스를 제거한다. 설정 갱신 이벤트는 Kafka를 사용하는 Spring Cloud Bus로 전달한다.

```text
POST /actuator/busrefresh
```

## cowork-gateway

`cowork-gateway`는 포트 `8080`의 WebFlux 기반 단일 외부 진입점이다. 일반 HTTP 요청은 JWT 인증 후 Eureka의 `lb://cowork-{service}` 대상으로 라우팅하고, 인증 정보를 하위 서비스 헤더에 덮어쓴다.

```text
Client
  → SecurityConfig / JWT 검증
  → AuthHeaderMutatingFilter / X-User-Id, X-User-Role 주입
  → Gateway route / RewritePath, StripPrefix, PrefixPath, Retry, RateLimit
  → ApiResponseWrapperFilter / JSON 응답 래핑
  → Client
```

WebSocket `/ws/**`는 별도 보안 체인을 사용한다. 브라우저 핸드셰이크 제약 때문에 쿠키 기반 JWT 변환과 Origin 검사를 적용한다.

### local 프로파일 라우팅

로컬 Compose에서 사용하는 `cowork-gateway-local.yml`의 주요 경로는 다음과 같다.

| 외부 경로                   | 대상              | 변환 또는 비고                                               |
|-----------------------------|-------------------|--------------------------------------------------------------|
| `/api/auth/**`              | authorization     | `/api` 제거, 10/20 rate limit                                |
| `/api/events/datagsm`       | authorization     | 공개 HMAC webhook                                            |
| `/api/users/**`             | user              | `/api` 제거, GET retry, 20/40 rate limit                     |
| `/api/teams/*/projects/**`  | project           | 일반 team 라우트보다 먼저 매칭                               |
| `/api/teams/**`             | team              | `/api` 제거, 20/40 rate limit                                |
| `/api/projects/**`          | project 또는 chat | 메시지 검색 경로는 chat으로 우선 라우팅                      |
| `/api/channels/**`          | channel 또는 chat | 메시지·파일·GitHub·slash-command 경로는 chat으로 우선 라우팅 |
| `/api/search/messages`      | chat              | `/chat` 접두사 추가                                          |
| `/api/search/channels`      | channel           | `/api` 제거                                                  |
| `/api/chats/**`             | chat              | legacy 경로, `/chat/**`로 rewrite                            |
| `/ws/chat/**`               | chat WebSocket    | 10/20 rate limit                                             |
| `/api/dms`                  | channel 또는 chat | POST 생성은 channel, 나머지는 chat                           |
| `/api/block/**`             | chat              | `/chat` 접두사 추가                                          |
| `/api/preferences/**`       | preference        | `/api` 제거                                                  |
| `/api/voice/webhook`        | voice             | 공개 webhook                                                 |
| `/api/voice/**`             | voice             | `/api` 제거, 10/20 rate limit                                |
| `/api/notifications/stream` | notification      | SSE 응답 타임아웃 비활성화                                   |
| `/api/notifications/**`     | notification      | `/api` 제거                                                  |

`dev` 설정에는 위 경로 외에 `/api/roadmaps/**` 라우트와 대부분의 HTTP 라우트에 `defaultCB` Circuit Breaker가 있다. 반면 `local` 설정은 Circuit Breaker를 붙이지 않는다. 프로파일 간 차이를 수정할 때 두 파일을 모두 검토한다.

### JWT와 공개 경로

Gateway만 JWT를 검증한다. 검증 후 아래 헤더를 기존 클라이언트 값과 무관하게 덮어쓴다.

```text
X-User-Id: <JWT subject>
X-User-Role: <JWT role>
```

현재 인증 없이 허용되는 경로는 다음과 같다.

- 모든 `OPTIONS` 요청
- `GET /api/auth/signin`, `GET /api/auth/callback`
- `POST /api/auth/token`, `POST /api/auth/refresh`
- `/actuator/**`, `/api/health`, `/fallback`
- `/swagger-ui.html`, `/swagger-ui/**`, `/webjars/**`, `/v3/api-docs/**`
- `POST /api/voice/webhook`
- `POST /api/events/datagsm`
- `GET /api/channels/oauth/callback/**`

하위 서비스는 JWT를 다시 파싱하지 않는다. 운영에서는 Gateway를 우회하는 서비스 포트를 외부에 노출하지 않는다.

### Retry, Rate Limit, Circuit Breaker

`user-service` GET 요청은 `BAD_GATEWAY`, `SERVICE_UNAVAILABLE` 응답에 최대 3회 재시도한다. backoff는 100ms에서 시작해 최대 500ms까지 2배로 증가한다.

Redis Token Bucket의 로컬 설정은 다음과 같다.

| 라우트                               | replenishRate | burstCapacity |
|--------------------------------------|--------------:|--------------:|
| authorization, chat WebSocket, voice |            10 |            20 |
| user, team, channel                  |            20 |            40 |

키는 인증된 요청이면 `X-User-Id`, 미인증 요청이면 클라이언트 IP다.

`dev`의 `defaultCB`는 최근 10개 요청에서 실패율 50%를 기준으로 열리고 10초 후 half-open으로 전환한다. fallback은 `/fallback`에서 HTTP 503을 반환한다.

### JSON 응답 래핑

`ApiResponseWrapperFilter`는 JSON 응답을 `CommonApiResponse<T>`로 통일한다.

```json
{
  "status": "OK",
  "code": 200,
  "message": "OK",
  "data": {}
}
```

다음 응답은 래핑하지 않는다.

- actuator, fallback, Swagger/OpenAPI 경로
- JSON이 아닌 응답
- 1 MiB를 초과하는 응답
- 이미 `code`, `status`, `message` 필드를 가진 응답

Chunked 응답도 수집 후 실제 크기를 확인해 래핑한다. SSE는 JSON 응답이 아니므로 그대로 전달된다.

### CORS와 Swagger

`local`과 `dev`의 CORS 허용 origin은 현재 `http://localhost:3000`이다. 허용 method는 `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS`이며 credential을 허용한다.

로컬 통합 Swagger UI는 `http://localhost:8080/swagger-ui.html`에서 확인한다. 등록 서비스와 원본 문서 경로는 `docs/api-documentation.md`를 참고한다.

### 임시 외부 호스트 연동

`EXTERNAL_HOST_URL`이 비어 있지 않으면 `ExternalRouteConfig`가 Eureka 미등록 호스트의 API, health, OpenAPI 경로를 Gateway에 추가한다. 기본값은 빈 문자열이라 비활성화된다. 제거 범위는 `docs/todo/07-cleanup/external-host-temp-integration.md`에 기록되어 있다.

## 기동 순서와 주요 환경 변수

Compose의 `depends_on`이 아래 순서를 보장한다.

```text
Kafka + kafka-init
Vault + vault-init
Redis
  → cowork-config
      → cowork-gateway 및 도메인 서비스
```

| 서비스         | 변수                                                           | 로컬 기본값 또는 용도        |
|----------------|----------------------------------------------------------------|------------------------------|
| config         | `SPRING_PROFILES_ACTIVE`                                       | Compose 기본 `local`         |
| config         | `VAULT_HOST`, `VAULT_PORT`, `VAULT_SCHEME`, `VAULT_TOKEN`      | Vault 연결                   |
| config         | `CONFIG_GIT_URI`, `CONFIG_GIT_USERNAME`, `CONFIG_GIT_PASSWORD` | `prod` Git 백엔드            |
| Config Server  | Kafka, Redis, Eureka와 Gateway route                            | native/Git 일반 설정         |
| gateway        | `EXTERNAL_HOST_*`                                              | 선택적 외부 호스트 임시 연동 |

JWT secret은 `vault-init`이 `secret/cowork-gateway`의 `jwt.secret`으로 기록하고 Config Server가 Gateway에 전달한다. 빈 값이면 Gateway 설정 바인딩 검증 단계에서 기동에 실패한다.
