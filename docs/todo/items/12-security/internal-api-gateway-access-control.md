# Gateway 내부 API 외부 노출 차단

- **서비스**: cowork-gateway, cowork-user, cowork-team, cowork-project 및 내부 HTTP client
- **우선순위**: 🔴 높음
- **파생 원본**: [외부 API 모듈 네임스페이스 통일](../10-api/public-route-namespace-migration.md)
- **현재 상태**: 공개 API와 같은 resource root에 있는 내부 operation이 Gateway catch-all route를 통해 외부에서 도달 가능함

## 문제

module namespace를 적용해도 `/api/user/users/**`, `/api/team/teams/**`, `/api/project/projects/**`처럼 resource root 전체를 Gateway에 연결하면 같은 root 아래의 내부 서비스용 operation도 계속 노출된다.

현재 확인된 대상은 다음과 같다.

| 소유 서비스 | 내부 downstream operation | 현재 내부 소비자 |
|---|---|---|
| user | `PUT /users/{userId}` | authorization 사용자 동기화 |
| user | `PATCH /users/{userId}/status` | authorization 접속 상태 동기화 |
| team | `GET /teams/{teamId}/members/{userId}/exists` | channel 팀 멤버 검증 |
| project | `GET /projects/{projectId}/team-id` | channel 프로젝트 소유 팀 조회 |
| project | `GET /projects/{projectId}/members/me` | chat 프로젝트 멤버십 검증; 외부 공개 필요 여부 미확정 |

`@Hidden` 또는 OpenAPI customizer는 Swagger에서 operation을 숨길 뿐 실제 요청을 차단하지 않는다. Gateway JWT 인증도 “로그인한 외부 사용자”의 호출을 막지 않으므로 내부 API 접근 통제로 사용할 수 없다.

## 목표 정책

- Gateway에는 외부 공개가 확정된 HTTP method와 path 조합만 positive allowlist로 등록한다.
- 내부 operation은 canonical module namespace에서도 외부에서 도달할 수 없어야 한다.
- 서비스 간 HTTP client는 Gateway를 거치지 않고 Eureka 또는 내부 service URL의 기존 downstream path를 계속 사용한다.
- Downstream 서비스는 Gateway가 전달한 `X-User-Id`·`X-User-Role`을 신뢰하는 기존 원칙을 유지하며 JWT를 직접 검증하지 않는다.
- 운영 환경에서 서비스 포트는 외부에 공개하지 않고 Gateway만 외부 진입점으로 유지한다.
- OpenAPI 노출 제어와 네트워크·라우팅 접근 제어를 별도 계층으로 검증한다.

## 할 일

### operation 분류

- 모든 외부 HTTP 모듈의 controller/router/handler를 HTTP method + absolute downstream path 단위로 inventory한다.
- 각 operation을 `public-authenticated`, `public-unauthenticated`, `internal-only`, `platform-only`로 분류한다.
- `GET /projects/{projectId}/members/me`의 웹 클라이언트 사용 여부를 사용자가 확인하고 공개 또는 내부 전용으로 확정한다.
- public root에 새 operation을 추가할 때 외부 공개 여부를 명시하도록 코드 리뷰 체크리스트를 추가한다.

### Gateway 차단 방식 확정

- root catch-all이 내부 operation과 섞이는 모듈은 method·path positive allowlist route로 교체한다.
- path variable과 하위 resource가 많은 경우에도 내부 path 몇 개만 차단하는 denylist에 의존할지 신중히 검토하고, 기본값은 새 operation을 자동 노출하지 않는 positive allowlist로 한다.
- unmatched 내부 operation은 다른 모듈 route나 fallback에 우연히 매칭되지 않고 404 또는 명시적으로 정한 비노출 응답을 반환하게 한다.
- local/prod route 정책을 동일하게 유지하고 Circuit Breaker 등 환경별 필터 차이만 허용한다.
- Gateway actuator route 조회나 오류 응답이 내부 service URL·토폴로지를 외부에 노출하지 않는지 확인한다.

### 서비스와 OpenAPI 정합

- 내부 service client의 base URL과 downstream path는 변경하지 않는다.
- Gateway-facing OpenAPI에서는 `internal-only` operation을 제거한다.
- 서비스 직접 OpenAPI에 내부 operation을 남길 경우 내부 전용임을 명시하고 외부 통합 Swagger에는 포함하지 않는다.
- 서비스 직접 포트가 운영 ingress, Compose port publish, 방화벽 규칙으로 외부에 노출되지 않는지 확인한다.
- Gateway 외부 차단이 서비스 간 호출 실패로 이어지지 않는 통합 테스트를 추가한다.

## 수정 예상 대상

- `cowork-config/src/main/resources/configs/cowork-gateway-local.yml`
- `cowork-config/src/main/resources/configs/cowork-gateway-prod.yml`
- `cowork-gateway/src/test/kotlin/com/cowork/gateway/GatewayConfigBindingTest.kt`
- cowork-user의 router·Gateway-facing `open_api.ex`
- cowork-team·cowork-project의 OpenAPI customizer 또는 operation visibility 설정
- authorization·channel·chat의 내부 HTTP client 회귀 테스트
- `docker-compose.yml`, `docker-compose.prod.yml` 및 운영 ingress·방화벽 설정 점검

## 검증

- 정상 JWT가 있어도 각 `internal-only` canonical URL은 Gateway에서 도달하지 않는다.
- JWT가 없거나 관리자 role인 경우에도 내부 operation이 외부에 열리지 않는다.
- 같은 resource root의 공개 operation은 정상 JWT로 계속 동작한다.
- authorization → user, channel → team/project, chat → project 직접 호출이 Gateway 변경 후에도 성공한다.
- 구 경로와 canonical 경로 모두에서 내부 operation이 의도한 서비스에 도달하지 않는다.
- Gateway-facing OpenAPI에 내부 operation이 없고, 공개 operation의 server + path는 canonical URL과 일치한다.
- 새 controller operation을 추가했을 때 allowlist에 명시하지 않으면 외부에 자동 노출되지 않는다.
- 운영 배포에서 서비스 직접 포트가 외부 네트워크에서 접근 불가능하다.

## 완료 조건

- 모든 Gateway-facing operation에 공개·내부 소유권이 method·path 단위로 정의되어 있다.
- 내부 operation은 인증 여부나 role과 관계없이 외부 Gateway에서 도달할 수 없다.
- 내부 API를 숨기는 데 `@Hidden`이나 Swagger 필터만 의존하지 않는다.
- 서비스 간 직접 호출과 내부 service discovery는 기존 경로로 정상 동작한다.
- local/prod Gateway 설정과 Gateway-facing OpenAPI가 같은 public allowlist를 표현한다.
- 내부 operation 추가 시 외부 자동 노출을 막는 회귀 테스트가 자동화되어 있다.
