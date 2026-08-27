# 외부 API 모듈 네임스페이스 통일

- **서비스**: cowork-gateway, cowork-config, 외부 HTTP API 모듈 10개, 웹 클라이언트·외부 provider
- **우선순위**: 🟠 중간
- **현재 상태**: 저장소 코드 전환 및 자동화 검증 완료. 외부 소비처·provider 운영 URL 전환과 staging 배포 검증 대기
- **외부 소비처 담당**: 웹·모바일·자동화 클라이언트와 provider 운영 설정은 저장소 외부 작업이며 사용자가 전환한다

> **2026-08-27 후속 상태:** authorization → user 내부 upsert와 다른 모노레포 서비스 간 HTTP
> 호출은 Kafka state projection 또는 authoritative aggregate 이전으로 제거됐다. 아래의 당시 구현 기록 중
> 해당 내부 URL 설명은 현재 계약이 아니며, GitHub App 관련 HTTP만 별도 보류 상태다.

## 진행 상태 (2026-08-25)

| 단계 | 상태 |
|---|---|
| 1. Gateway 경로·Swagger·Git 이력 inventory | ✅ 완료 |
| 2. 외부 네임스페이스 정책 확정 | ✅ 완료 |
| 3. cowork-chat HTTP·GraphQL·health·AsyncAPI 선행 전환 | ✅ 코드 완료 |
| 4. authorization·user·team·channel·project·roadmap·voice·notification·preference 전환 | ✅ 코드 완료 |
| 5. 서비스별 Gateway-facing OpenAPI 정합 | ✅ 코드·계약 테스트 완료 |
| 6. 저장소 내부 OAuth callback URL 생성 코드 전환 | ✅ 코드·단위 테스트 완료 |
| 7. 웹·모바일·자동화 클라이언트와 provider 등록 URL 전환 (사용자 담당) | ⏳ 사용자 전환 대기 |
| 8. staging 원자적 배포 및 구 경로 negative 검증 | ⏳ 외부 소비처 전환 후 수행 |

## 저장소 구현 결과 (2026-08-25)

- Gateway local/prod route를 모두 `/api/{module-name}{downstream-path}`와 `StripPrefix=2`로 전환하고 두 환경의 route 계약을 회귀 테스트로 고정했다.
- Gateway JWT 공개 예외를 canonical path와 최소 HTTP method로 이동했다. 구 DataGSM 경로를 포함한 비정상 path·method가 인증 없이 통과하지 않는 보안 체인 테스트를 추가했다.
- Spring, Go, Elixir, Vert.x 정적 명세의 Gateway-facing server/basePath, Bearer JWT, 공개·내부 operation 노출을 정합화했다. Go 생성 명세에서는 외부 route가 없는 직접 health operation을 제외했다.
- Channel OAuth authorize·token 교환이 동일한 canonical callback URL을 사용하도록 공통화했다.
- 중앙 Gateway·API 문서와 환경변수 설명을 새 계약에 맞췄다. 서비스 간 직접 호출 경로, WebSocket `/ws/chat/**`, 통합 문서 `/v3/api-docs/{module}`는 변경하지 않았다.
- 모니터링과 내부 API 접근 통제는 각각 2번·3번 파생 TODO로 분리했으며 이번 완료 범위에 포함하지 않는다.

### 자동화 검증

- JVM Gradle: Gateway, Channel, Team, Roadmap 전체 테스트 통과
- Project Maven: `clean test` 95개 통과
- Go: Authorization, Voice, Notification `go test ./...` 통과
- Elixir User: `mix test` 3개 통과
- Preference Amper: `check` 3개 통과

## 결정된 외부 라우트 정책

HTTP API의 canonical 경로는 다음 규칙을 따른다.

```text
/api/{module-name}{downstream-path}
```

- `module-name`은 `cowork-*` 모듈명의 suffix인 단수형 이름이다: `authorization`, `user`, `team`, `channel`, `project`, `roadmap`, `chat`, `voice`, `notification`, `preference`.
- `downstream-path`는 각 하위 서비스의 router/controller/OpenAPI가 실제로 정의한 절대 경로다. Gateway는 이를 줄이거나 이름을 바꾸지 않는다.
- Gateway가 제거하는 것은 앞의 `/api/{module-name}` 두 세그먼트뿐이다. 예: `/api/user/users/me` → `/users/me`, `/api/authorization/auth/token` → `/auth/token`, `/api/voice/live/channels/{id}` → `/live/channels/{id}`.
- 하위 path가 모듈명과 겹쳐도 생략하지 않는다. 예: Chat의 `/chat/dms`는 `/api/chat/chat/dms`, Voice의 `/voice/channels/{id}`는 `/api/voice/voice/channels/{id}`다.
- 외부 module은 실제 요청 처리 서비스의 소유권을 따른다. Channel의 `POST /dms`는 `/api/channel/dms`, Chat의 `GET /chat/dms`는 `/api/chat/chat/dms`다.
- 하위 서비스의 직접 경로, 서비스 간 HTTP client 경로, OpenAPI path는 바꾸지 않는다. Gateway는 원칙적으로 `StripPrefix=2`만 사용한다.
- route predicate는 공개 downstream root만 열거한다. `/api/{module-name}/**` catch-all로 `/internal`, `/metrics`, `/actuator`, 서비스 직접 Swagger 경로까지 노출하지 않는다.
- deprecated alias는 두지 않는다. 모듈별 전환 시 Gateway와 소비자를 원자적으로 배포하고 기존 경로는 즉시 제거한다.

### 정책 예외

- WebSocket은 기존 프로토콜 규칙 `/ws/{module}/**`를 유지한다.
- 통합 문서 프록시는 `/v3/api-docs/{module}`를 유지한다.
- `/api/health`, `/actuator/**`, `/swagger-ui/**`, `/fallback`은 도메인 API가 아닌 Gateway·플랫폼 공용 경로로 예약한다.
- 서비스 내부 `/metrics`와 직접 healthcheck 경로는 외부 API 정책 대상에서 제외한다. 외부에 필요한 health만 명시적 라우트로 노출한다.

## 파생 TODO와 범위 경계

- [Gateway canonical API 계약 모니터링](../11-monitoring/gateway-canonical-api-monitoring.md): 기존 내부 health·metrics 수집은 유지하면서 canonical Gateway 경로의 실제 도달성을 별도로 감시한다.
- [Gateway 내부 API 외부 노출 차단](../12-security/internal-api-gateway-access-control.md): public root와 같은 하위 path에 섞인 내부 operation을 method·path 단위로 Gateway에서 차단한다.
- 이 문서는 외부 경로 네임스페이스와 Gateway-facing OpenAPI 정합을 담당한다. 모니터링 설계와 내부 API 차단 방식의 상세 구현·완료 조건은 위 파생 TODO에서 관리한다.
- 웹·모바일·자동화 클라이언트 endpoint와 DataGSM·OAuth provider·LiveKit의 운영 등록 URL은 사용자가 전환한다. 저장소 작업은 목표 URL과 배포 체크리스트를 제공하고, staging 전환 전에 사용자 확인을 받는다.

## Git 이력과 원인

이번에 확정한 strict-prefix 원칙의 문구 자체는 저장소 문서와 Git commit에서 찾지 못했다. 따라서 아래 이력은 원칙의 출처가 아니라, 저장소 구현이 이미 원칙과 어긋난 상태로 시작해 기능별 예외가 누적된 과정의 근거다.

| commit | 변경 |
|---|---|
| `5d24aa0` | 가장 이른 Gateway 구현에서 `/api/auth/**`, `/api/users/**`와 `StripPrefix=1` 도입 |
| `e649cb9` | 가장 이른 Gateway 문서도 `/api` 하나만 제거하는 현재 구현을 설명 |
| `21002d59` | `/api/voice/**`와 공개 voice webhook 추가 |
| `07c987b5` | Chat이 처음부터 `/chat` global prefix를 사용했지만 당시 Gateway `/api/chats/**`는 실제 경로와 불일치 |
| `d7c4bc63` | Chat을 `/api/channels/**` 하위로 분산하고 `/api/chats/**`를 legacy로 분류 |
| `0cdbcb69`, `3a1351f0` | Chat·Channel 검색을 `/api/projects/**`, `/api/search/**`에 추가 |
| `0117f391` | DM·block을 `/api/dms`, `/api/block` root에 추가 |
| `bbe88709` | Authorization webhook을 `/api/events/datagsm`에 별도 추가 |
| `3476c5b8`, `28f01541` | Voice `/live/**` 구현 후 `/api/live/**` Gateway 누락을 사후 보완 |
| `69ca7b0e` | WebSocket만 `/ws/{module}`로 명시적 표준화 |
| `911e7d59`, `94766074` | local/prod 라우트와 Swagger 목록을 사후 정합화 |

`auth`, 복수형 리소스명, `live`, `events`, `search`, `dms`를 module prefix 없이 노출해야 한다는 별도 설계 근거는 확인되지 않았다.

## CORS 보고 분석 결론

최초 보고의 403은 Chat과 NestJS 내부 CORS가 아니라 Gateway 단에서 발생했다. 라우트 정책과 CORS 설정을 분리해 고쳐야 한다.

- 보고서의 `GET /api/chat/dms`는 strict-prefix 정책상 올바른 Chat 경로가 아니다. 하위 서비스 경로 `/chat/dms`를 보존한 canonical URL은 `GET /api/chat/chat/dms`다.
- 저장소의 Chat 라우트가 `/api/channels`, `/api/projects`, `/api/search`, `/api/dms`, `/api/block`에 흩어져 module prefix 규칙을 깨고 있었다.
- Spring Cloud Gateway 5로 올린 후에도 설정이 이전 `spring.cloud.gateway` prefix에 남아 새 버전의 route·global CORS binder가 읽지 못하는 코드 결함이 있었다.
- local/prod를 `spring.cloud.gateway.server.webflux`로 옮기고 `globalcors.add-to-simple-url-handler-mapping: true`를 추가해 route predicate와 관계없이 preflight에 전역 CORS를 적용하도록 수정했다.
- 로컬에서 수정 설정으로 Gateway를 실제 기동했을 때 허용 origin preflight는 200 + ACAO, 비허용 origin은 403을 반환했다.

## 전체 경로 전환 표

| 공개 모듈 | 현재 외부 경로 | 목표 외부 경로 | 하위 서비스 변환 |
|---|---|---|---|
| authorization | `/api/auth/**` | `/api/authorization/auth/**` | `/auth/**` |
| authorization | `/api/events/datagsm` | `/api/authorization/events/datagsm` | `/events/datagsm` |
| user | `/api/users/**` | `/api/user/users/**` | `/users/**` |
| user 내부 API | 기존 public root 내부 command | 제거 완료 | authorization command를 user가 커밋하고 Kafka 결과를 반환 |
| team | `/api/teams/**` 중 team 소유 API | `/api/team/teams/**` | `/teams/**` |
| project | `/api/projects/**` 중 project 소유 API | `/api/project/projects/**` | `/projects/**` |
| project | `/api/teams/{teamId}/projects/**` | `/api/project/teams/{teamId}/projects/**` | `/teams/{teamId}/projects/**` |
| channel | `/api/channels/**` 중 channel 소유 API | `/api/channel/channels/**` | `/channels/**` |
| channel | `/api/teams/{teamId}/channels/**` | `/api/channel/teams/{teamId}/channels/**` | `/teams/{teamId}/channels/**` |
| channel | `/api/projects/{projectId}/channels/**` | `/api/channel/projects/{projectId}/channels/**` | `/projects/{projectId}/channels/**` |
| channel | `/api/search/channels` | `/api/channel/search/channels` | `/search/channels` |
| channel | `POST /api/dms` | `POST /api/channel/dms` | `/dms` |
| channel OAuth | `/api/channels/oauth/callback/{provider}` | `/api/channel/channels/oauth/callback/{provider}` | `/channels/oauth/callback/{provider}` |
| roadmap | `/api/roadmaps/**` | `/api/roadmap/roadmaps/**` | `/roadmaps/**` |
| preference | `/api/preferences/**` | `/api/preference/preferences/**` | `/preferences/**` |
| notification | `/api/notifications/**` | `/api/notification/notifications/**` | `/notifications/**` |
| voice | `/api/voice/**` | `/api/voice/voice/**` | `/voice/**` |
| voice | `/api/live/**` | `/api/voice/live/**` | `/live/**` |
| chat | `/api/chats/**`, `/api/channels/{id}/...`, `/api/teams/{id}/unread` | `/api/chat/chat/**` | `/chat/**` |
| chat | `/api/projects/{id}/messages/search`, `/api/search/messages` | `/api/chat/chat/projects/{id}/messages/search`, `/api/chat/chat/search/messages` | `/chat/**` |
| chat | `GET·DELETE /api/dms`, `/api/block/**` | `/api/chat/chat/dms`, `/api/chat/chat/block/**` | `/chat/**` |
| chat | 직접 `/health`, `/asyncapi.json` | `/api/chat/health`, `/api/chat/asyncapi.json` | 경로 그대로 |
| chat | 직접 `/graphql` | `/api/chat/graphql` | `/graphql` |
| WebSocket | `/ws/chat/**` | 변경 없음 | Socket.IO path 유지 |

`/api/notifications/stream` → `/api/notification/notifications/stream`은 일반 notification 라우트보다 먼저 매칭하고 `response-timeout: -1`을 보존한다. `/api/voice/webhook`은 `/api/voice/voice/webhook`으로 이동하면서 공개 인증·rate-limit 예외를 유지한다. Channel의 `POST /dms`와 Chat의 `GET·DELETE /chat/dms`는 서로 다른 모듈 경로로 분리한다.

## cowork-chat 선행 전환

2026-08-25에 다음 코드 변경을 선행했다.

- local/prod에서 분산된 Chat HTTP route id와 `/api/chats/**` legacy를 제거하고 Chat 공개 HTTP root를 하나의 route로 통합했다.
- route는 `/api/chat/chat/**`, `/api/chat/health`, `/api/chat/graphql`, `/api/chat/asyncapi.json`만 허용하고 `StripPrefix=2`를 적용한다. `/api/chat/chat/dms`는 `/chat/dms`, `/api/chat/health`는 `/health`로 전달된다.
- `/api/chat/metrics`, `/api/chat/api`, `/api/chat/api-json`처럼 공개 계약이 아닌 서비스 root는 Gateway route에 포함하지 않는다.
- Channel이 구현한 `POST /dms`는 Chat 경로로 가져오지 않았다. 기존 `/api/dms`를 유지하며 Channel 전환 시 `/api/channel/dms`로 옮긴다.
- `GET /api/chat/health`와 `GET /api/chat/chat/health/ready`를 JWT 없이 허용한다.
- GraphQL의 기존 하위 경로 `/graphql`을 `/api/chat/graphql`로 연결하고 Gateway `CommonApiResponse` 래핑에서 제외했다.
- `/api/chat/asyncapi.json`을 공개 문서 경로로 추가하고 응답 래핑에서 제외했다.
- Chat OpenAPI는 `server: /api/chat`과 실제 하위 path(`/chat/**`, `/health`)를 결합하고 Bearer JWT를 사용하며 health operation은 공개로 표시한다.
- 구 Chat route id가 없고 strict-prefix route가 Spring Cloud Gateway 5 설정에 바인딩되는지 local/prod 회귀 테스트를 추가했다.

남은 Chat 작업은 웹 클라이언트 경로 변경이다. `POST /dms`는 Channel 전환과 Channel OpenAPI 정합 작업에서 처리한다.

## Gateway와 보안 규칙 변경

모듈별로 local/prod를 항상 같이 수정하고 다음을 검증한다.

- predicate가 겹치는 경우 더 구체적인 root·method route에 더 작은 `order` 값을 설정해 우선순위를 높인다.
- module별 공개 root allowlist를 사용하고 `/internal`, metrics, 서비스 직접 문서 root를 새 namespace로 노출하지 않는다.
- public root와 같은 하위 path에 섞인 내부 operation의 실제 외부 차단은 파생 [Gateway 내부 API 외부 노출 차단](../12-security/internal-api-gateway-access-control.md)에서 method·path 단위로 구현한다. `@Hidden` 또는 OpenAPI customizer는 문서 제외 수단일 뿐 접근 통제로 간주하지 않는다.
- namespace 전환 시 내부 operation을 Gateway-facing OpenAPI에서 제외한다. Kafka projection으로 대체된 조회 operation은 서비스에서도 제거한다.
- public 외부 경로에서 기존 하위 경로로의 rewrite를 route test로 고정한다.
- 이전 경로가 우연히 다른 서비스 catch-all에 매칭되지만 않게 도달 target까지 검증한다.
- `SecurityConfig` 공개 경로를 canonical URL로 한 번에 이동한다.
- 이미 제거된 Authorization signin·callback을 허용하던 stale matcher는 2026-08-25에 제거했다.
- token·refresh, DataGSM webhook, Channel OAuth callback, Voice webhook, Chat health·AsyncAPI의 비인증 범위를 method까지 최소 권한으로 고정한다.

## Swagger/OpenAPI 변경

통합 Swagger는 원본 명세를 그대로 프록시하므로 각 명세의 `server + path`가 실제 Gateway canonical URL과 같아야 한다.

- `server`/`basePath`를 `/api/{module}`로 두고 각 path는 하위 서비스가 정의한 값을 그대로 유지한다. 두 값을 결합한 결과가 canonical URL이다.
- Gateway Bearer JWT scheme을 제공하고 실제 public operation만 security requirement를 빈 배열로 override한다.
- Go 서비스는 handler annotation 수정 후 `make swagger-gen`으로 `docs.go`, `swagger.json`, `swagger.yaml`을 같이 재생성한다.
- Authorization과 Notification의 생성 명세에 포함된 직접 `/health` operation은 Gateway-facing Swagger에서 제거한다. 두 경로는 해당 모듈의 공개 route allowlist에 포함하지 않는다.
- cowork-user의 `open_api.ex`, cowork-preference의 `openapi.json`은 정적 명세를 직접 갱신한다.
- cowork-user 명세의 공개 `GET /users/batch`를 유지한다. 제거된 내부 `PUT /internal/users/{id}`는 명세와 router 어디에도 두지 않으며, 접속 상태 변경은 본인용 `PATCH /users/me/status`만 공개한다.
- Spring 모듈은 Gateway-facing server/path customizer를 추가하고, Channel의 `sdk.swagger.paths-to-match`에 `/projects/**`, `/search/**`, `/dms`를, Project에 `/teams/**`를 포함한다.
- 통합 Swagger `Try it out`으로 생성된 URL·Authorization header·public operation을 서비스별로 검증한다.

## OAuth·webhook·클라이언트 영향

- Channel OAuth callback URL 생성 코드는 현재 bare `PUBLIC_API_BASE_URL`에 `/channels/oauth/callback/...`만 덧붙여 기존 Gateway의 `/api`도 누락할 수 있다. 목표는 `{PUBLIC_API_BASE_URL}/api/channel/channels/oauth/callback/{provider}`로 명시하고 GitHub·Notion·Jira·Google·Facebook provider 등록 URI를 같이 바꾼다.
- DataGSM 등록 webhook을 `/api/authorization/events/datagsm`으로 바꾼다.
- LiveKit webhook을 `/api/voice/voice/webhook`으로 바꾼다.
- 웹 클라이언트와 모바일·자동화 소비자의 모든 endpoint 및 provider 운영 등록 URL은 사용자가 모듈별 canonical URL로 전환한다.

### 사용자 외부 전환 체크리스트

- [ ] 웹·모바일·자동화 클라이언트의 REST base/path를 위 전체 경로 전환 표의 목표 경로로 교체
- [ ] DataGSM webhook을 `{PUBLIC_API_BASE_URL}/api/authorization/events/datagsm`으로 교체
- [ ] GitHub·Notion·Jira·Google·Facebook redirect URI를 `{PUBLIC_API_BASE_URL}/api/channel/channels/oauth/callback/{provider}`로 교체 (`provider`는 소문자)
- [ ] LiveKit webhook을 `{PUBLIC_API_BASE_URL}/api/voice/voice/webhook`으로 교체
- [ ] Gateway와 외부 소비처·provider 설정을 같은 release 창에서 배포할 수 있도록 rollback snapshot 확보
- [ ] 사용자 전환 완료 확인 후 staging에서 canonical·구 경로 negative·CORS·JWT/public·SSE·GraphQL·OAuth 검증 수행

## 구현 순서

1. 모듈별 현재 endpoint·HTTP method·downstream target·public 인증·OpenAPI path를 snapshot test로 고정한다.
2. 해당 모듈의 Gateway local/prod route, route order, rewrite, `SecurityConfig`를 canonical URL로 교체한다.
3. 각 런타임의 Gateway-facing Swagger/OpenAPI server·path·security를 동일 배포 단위에서 바꾼다.
4. 보류된 GitHub App 직접 URL은 변경하지 않았음을 확인하고, 저장소 내부 OAuth·webhook URL 생성 코드를 canonical URL로 이동한다.
5. 사용자에게 최종 endpoint 변경표를 전달하고 웹·모바일·자동화 클라이언트와 provider 운영 등록 URL의 전환 완료를 확인한다.
6. canonical 요청, 구 경로 negative, CORS preflight, JWT/public matcher, Swagger `Try it out`을 staging에서 검증한다.
7. Gateway·클라이언트·provider 설정을 원자적으로 배포하고, 문제 시 이전 Gateway image와 클라이언트 version을 함께 rollback한다.

모듈 권장 순서는 authorization → user → voice → notification·preference → team·project·channel·roadmap이다. team·project·channel은 현재 predicate가 서로 겹치므로 같은 release에서 전환한다.

## 수정 대상

### Gateway·Config

- `cowork-config/src/main/resources/configs/cowork-gateway-local.yml`
- `cowork-config/src/main/resources/configs/cowork-gateway-prod.yml`
- `cowork-gateway/src/main/kotlin/com/cowork/gateway/security/SecurityConfig.kt`
- Gateway route·CORS·security 회귀 테스트

### OpenAPI·서비스 코드

- cowork-authorization·cowork-voice·cowork-notification handler annotation과 생성된 `docs/docs.go`, `docs/swagger.json`, `docs/swagger.yaml`
- `cowork-user/lib/cowork_user/open_api.ex`
- `cowork-preference/src/main/resources/openapi.json`
- cowork-team·cowork-channel·cowork-project·cowork-roadmap OpenAPI customizer 및 Swagger path include 설정
- `cowork-chat/src/main.ts`

### OAuth·문서·외부 설정

- cowork-channel OAuth callback URL 생성 서비스 2개와 테스트
- `.env.example`, `docker-compose.prod.yml`의 `PUBLIC_API_BASE_URL` 계약
- 외부 경로 계약을 직접 설명하는 중앙 문서만 갱신하고 모듈 README에는 API 경로를 중복 기록하지 않는다.
- 사용자 전달용 웹·모바일·자동화 클라이언트 endpoint, DataGSM webhook, OAuth provider redirect URI, 운영 LiveKit webhook 변경표

## 배포 영향과 rollback

별칭 없는 전환이므로 새 클라이언트와 이전 Gateway, 이전 클라이언트와 새 Gateway는 서로 호환되지 않을 수 있다.

- 배포 전 현재 Gateway image tag·Config commit·클라이언트 version·provider URL을 rollback snapshot으로 남긴다.
- 한 모듈의 Gateway, OpenAPI, 웹 클라이언트, webhook/OAuth 설정을 하나의 release 창에서 전환한다.
- 문제 시 deprecated route를 임시 복구하는 대신 이전 Gateway·클라이언트·provider 설정 세트를 함께 rollback한다.
- CORS 허용 origin은 외부 경로와 독립적인 정책이므로 현재 명시적 allowlist를 유지한다.

## 검증

- local/prod의 route id 집합과 rewrite·order 차이가 의도된 Circuit Breaker 제외 외에 동일한지 검증한다.
- 모든 canonical path에 대해 허용 origin `OPTIONS` 응답이 200과 `Access-Control-Allow-Origin`을 반환하고, 비허용 origin은 403인지 확인한다.
- 인증 API는 JWT 없이 401, 정상 JWT로 하위 서비스에 `X-User-Id`·`X-User-Role`이 주입되는지 확인한다.
- public API는 method와 path가 정확히 일치할 때만 JWT 없이 통과하는지 확인한다.
- Channel `POST /api/channel/dms`, Chat `GET·DELETE /api/chat/chat/dms`가 각각 올바른 서비스로 가고 GraphQL은 표준 `{data, errors}` envelope를 유지하는지 확인한다.
- Notification SSE는 `response-timeout: -1`과 streaming content type을 유지하는지 확인한다.
- 구 경로가 404이거나 적어도 기존 소유 서비스에 도달하지 않는지 negative test로 검증한다.
- 각 OpenAPI의 `server + path`, Bearer requirement, public operation을 파싱하고 통합 Swagger `Try it out`을 실행한다.
- OAuth authorize URL과 callback URL이 provider에 등록한 canonical URI와 바이트 단위로 같은지 확인한다.
- 사용자가 웹·모바일·자동화 클라이언트와 provider 운영 등록 URL의 전환 완료를 확인했는지 배포 체크리스트에 기록한다.

## 완료 조건

- 모든 외부 HTTP API가 `/api/{module-name}{downstream-path}` 형태로 소유 모듈에 라우팅되고 downstream path가 보존된다.
- `/ws/{module}/**`, `/v3/api-docs/{module}`, 플랫폼 공용 경로 외에 모듈 없는 외부 API root가 남지 않는다.
- deprecated alias 없이 구 경로 소비자·OAuth·webhook이 전부 canonical URL로 전환된다.
- local/prod Gateway, `SecurityConfig`, OpenAPI, 웹 클라이언트 계약이 같은 경로 정책을 표현한다.
- 통합 Swagger의 모든 `Try it out`이 실제 Gateway canonical URL을 호출한다.
- canonical·negative·CORS·JWT/public·streaming·GraphQL·OAuth 회귀 테스트가 자동화되어 통과한다.
- 무별칭 전환과 rollback 절차가 배포 runbook에 반영된다.
- 파생 TODO의 완료 여부는 각 문서에서 별도로 추적하며, namespace 마이그레이션 완료가 모니터링 또는 내부 API 접근 통제 완료를 대신하지 않는다.
