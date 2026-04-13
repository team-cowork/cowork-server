# cowork-gateway & cowork-config 서비스 분석

## cowork-config

### 개요

`cowork-config`는 **Config Server + Eureka Server를 단일 프로세스에서 겸임**하는 서비스입니다.

```kotlin
@EnableConfigServer   // Spring Cloud Config Server
@EnableEurekaServer   // Netflix Eureka Service Registry
class CoworkConfigApplication
```

포트 `8761`에서 실행되며, MSA 전체 서비스가 이 서버를 **가장 먼저** 띄워야 합니다.

---

### Config Server 기능

설정을 여러 백엔드에서 **우선순위 순서대로** 조회하는 Composite 방식을 사용합니다.

#### dev 프로필 (기본)

```
요청 → [1] Vault (민감 정보) → [2] classpath:/configs/ (일반 설정)
```

| 백엔드 | 역할 | 경로 |
|--------|------|------|
| Vault KV v2 | DB 비밀번호, JWT secret 등 민감 값 | `secret/{application}` |
| native (classpath) | 라우팅, Eureka, 공통 설정 | `classpath:/configs/` |

#### prod 프로필

```
요청 → [1] Vault (민감 정보) → [2] Git 레포 (일반 설정)
```

native 대신 **Git 레포지토리**에서 설정을 불러옵니다. `CONFIG_GIT_URI`, `CONFIG_GIT_USERNAME`, `CONFIG_GIT_PASSWORD` 환경 변수로 지정합니다.

---

### Vault 초기화

`vault-init.sh` 스크립트로 로컬 개발 환경을 초기화합니다.

```sh
VAULT_ADDR=http://localhost:8200 VAULT_TOKEN=dev-root-token sh vault-init.sh
```

스크립트가 설정하는 시크릿:

| Vault 경로 | 저장 값 |
|-----------|---------|
| `secret/application` | `jwt.secret` (공통, 전 서비스 공유) |
| `secret/cowork-user` | DB URL + 비밀번호 |
| `secret/cowork-authorization` | DB URL + 비밀번호 |

> `jwt.secret`은 `secret/application`에 **한 번만** 저장하고, gateway를 포함한 모든 서비스가 공통 경로에서 주입받습니다.

---

### Eureka Server 기능

`cowork-config`가 Eureka Server를 내장하므로, **별도 Eureka 서비스 없이** 서비스 디스커버리를 제공합니다.

```yaml
eureka:
  client:
    register-with-eureka: false   # 자기 자신은 등록 안 함
    fetch-registry: false
  server:
    enable-self-preservation: false
    eviction-interval-timer-in-ms: 5000  # 5초마다 dead 인스턴스 제거
```

---

### Spring Cloud Bus

Kafka를 통해 **설정 변경을 전 서비스에 브로드캐스트**합니다.

```
POST /actuator/busrefresh  →  Kafka  →  모든 서비스 설정 자동 갱신
```

---

## cowork-gateway

### 개요

`cowork-gateway`는 포트 `8080`에서 실행되는 **WebFlux 기반 Reactive API Gateway**입니다. 모든 외부 요청의 단일 진입점으로, JWT 인증 → 라우팅 → 응답 래핑까지 처리합니다.

---

### 요청 처리 흐름

```
Client Request
    │
    ▼
[SecurityConfig] JWT 검증 (Bearer Token)
    │  인증 실패 → 401
    ▼
[AuthHeaderMutatingFilter] X-User-Id, X-User-Role 헤더 주입
    │
    ▼
[Spring Cloud Gateway] 라우팅 (Eureka lb://)
    │  ├─ CircuitBreaker (Resilience4j)
    │  ├─ Retry (GET 요청)
    │  └─ RateLimiter (Redis)
    ▼
[ApiResponseWrapperFilter] 응답을 CommonApiResponse로 래핑
    │
    ▼
Client Response
```

---

### 라우팅 구성 (`cowork-gateway-dev.yml`)

모든 라우트는 `StripPrefix=1`으로 `/api` 접두사를 제거 후 하위 서비스로 전달합니다.

| 라우트 ID | 경로 | 대상 서비스 | CB | Retry | RateLimit |
|----------|------|------------|:--:|:-----:|:---------:|
| `authorization-service` | `/api/auth/**` | `cowork-authorization` | - | - | - |
| `user-service` | `/api/users/**` | `cowork-user` | O | O | O |
| `team-service` | `/api/teams/**` | `cowork-team` | O | - | - |
| `project-service` | `/api/projects/**` | `cowork-project` | O | - | - |
| `channel-service` | `/api/channels/**` | `cowork-channel` | O | - | - |
| `chat-service` | `/api/chats/**` | `cowork-chat` | O | - | - |

> `authorization-service`는 현재 설정에서 Circuit Breaker나 Rate Limiter가 적용되어 있지 않습니다. 인증 여부와 CB 적용은 별개이며, 추후 필요에 따라 추가할 수 있습니다.

---

### JWT 인증

`JwtServerAuthenticationConverter`가 `Authorization: Bearer <token>` 헤더에서 토큰을 추출하고 검증합니다.

```kotlin
// Claims 추출
val userId = claims.subject
val role   = claims.get("role", String::class.java) ?: "ROLE_USER"
```

검증 완료 후 `JwtReactiveAuthenticationManager`는 Authentication 객체를 그대로 통과시킵니다 (검증은 Converter에서 이미 완료).

**인증 통과 후**, `AuthHeaderMutatingFilter`가 SecurityContext에서 정보를 꺼내 하위 서비스 요청에 헤더를 **덮어씁니다** (외부 조작 방지):

```
X-User-Id: <userId from JWT>
X-User-Role: <role from JWT>
```

**인증 없이 허용되는 경로:**
- `POST /api/auth/**`
- `/actuator/**`
- `/fallback`

---

### Circuit Breaker (Resilience4j)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      defaultCB:
        slidingWindowSize: 10              # 최근 10개 요청 기준
        failureRateThreshold: 50           # 실패율 50% 초과 시 OPEN
        waitDurationInOpenState: 10s       # 10초 후 HALF-OPEN
        permittedNumberOfCallsInHalfOpenState: 3
        automaticTransitionFromOpenToHalfOpenEnabled: true
```

서킷이 OPEN되면 `/fallback` 엔드포인트로 포워딩되어 503 응답을 반환합니다:

```json
{
  "status": "SERVICE_UNAVAILABLE",
  "code": 503,
  "message": "서비스가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도해주세요."
}
```

---

### Retry (user-service만 적용)

```yaml
retries: 3
statuses: BAD_GATEWAY, SERVICE_UNAVAILABLE
methods: GET            # GET만 재시도 (idempotent 보장)
backoff:
  firstBackoff: 100ms
  maxBackoff: 500ms
  factor: 2             # 지수 백오프: 100ms → 200ms → 400ms
```

---

### Rate Limiting (user-service만 적용)

Redis Token Bucket 알고리즘 기반:

```yaml
redis-rate-limiter.replenishRate: 20    # 초당 20개 토큰 보충
redis-rate-limiter.burstCapacity: 40    # 순간 최대 40개
redis-rate-limiter.requestedTokens: 1
```

**Rate Limit 키 전략 (`userKeyResolver`):**
- 인증된 요청 → `X-User-Id` 기준 (사용자별 제한)
- 미인증 요청 → 클라이언트 IP 기준 (fallback)

---

### 응답 통일화 (`ApiResponseWrapperFilter`)

모든 JSON 응답을 `CommonApiResponse<T>` 포맷으로 래핑합니다.

```json
{
  "status": "OK",
  "code": 200,
  "message": "OK",
  "data": { ... }
}
```

**래핑 예외 조건:**
- `/actuator/**`, `/fallback` 경로
- JSON이 아닌 Content-Type
- `Content-Length` 헤더가 없는 응답 (Chunked Transfer-Encoding 등 — 크기 불명으로 버퍼링 불가)
- Content-Length > 1MB (OOM 방지)
- 이미 `CommonApiResponse` 형태인 응답 (중복 래핑 방지)

---

### CORS 설정 (dev)

```yaml
allowedOrigins: "http://localhost:3000"
allowedMethods: GET, POST, PUT, PATCH, DELETE, OPTIONS
allowedHeaders: "*"
allowCredentials: true
maxAge: 3600
```

---

## 배포 순서 및 의존 관계

```
[1] Vault         → 시크릿 저장소 (가장 먼저 실행)
[2] Kafka         → Config Bus, 이벤트 스트리밍
[3] Redis         → Gateway Rate Limiter
[4] cowork-config → Config Server + Eureka (포트 8761)
[5] 각 도메인 서비스 → Config에서 설정 fetch, Eureka 등록
[6] cowork-gateway → Config에서 라우팅/JWT 설정 fetch (포트 8080)
```

> `cowork-config`가 Config Server와 Eureka Server를 겸하므로, **절대 가장 먼저** 기동해야 합니다. gateway의 `fail-fast: true` 설정으로 인해 config 서버 미기동 시 gateway가 즉시 시작 실패합니다.

---

## 환경 변수 요약

### cowork-config

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `SPRING_PROFILES_ACTIVE` | `dev` | `dev` / `prod` |
| `VAULT_HOST` | `localhost` | Vault 호스트 |
| `VAULT_PORT` | `8200` | Vault 포트 |
| `VAULT_TOKEN` | `dev-root-token` | Vault 인증 토큰 |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka 주소 |
| `CONFIG_GIT_URI` | — | prod 전용, 설정 Git 레포 URL |
| `CONFIG_GIT_USERNAME` | — | prod 전용 |
| `CONFIG_GIT_PASSWORD` | — | prod 전용 |

### cowork-gateway

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Config Bus용 Kafka |
| `REDIS_HOST` | `localhost` | Rate Limiter용 Redis |
| `REDIS_PORT` | `6379` | Redis 포트 |

> `jwt.secret`은 환경 변수가 아닌 **Vault `secret/application`** 경로에서 자동 주입됩니다.