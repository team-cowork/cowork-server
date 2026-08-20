# cowork-gateway

## 역할

외부 요청의 유일한 진입점인 Reactive API Gateway입니다.

- JWT 검증 후 `X-User-Id`와 `X-User-Role`을 하위 서비스에 전달
- Eureka 기반 `lb://cowork-{service}` 라우팅과 경로 재작성
- Redis 기반 요청 속도 제한
- Resilience4j Circuit Breaker·Retry와 공통 fallback 응답
- JSON 응답을 `CommonApiResponse` 형식으로 래핑
- 서비스별 OpenAPI를 모은 Swagger UI 제공
- CORS를 전역에서 처리
- Config Bus를 통한 라우팅·보안 설정 갱신

하위 서비스는 JWT를 다시 검증하지 않고 Gateway가 전달한 사용자 헤더를 신뢰합니다. 운영 환경에서는 Gateway를 우회하는 서비스 경로를 외부에 노출하지 않습니다.

## WebSocket 인증 (`/ws/{module}`)

`/ws/**` 요청은 전용 보안 체인에서 `Authorization` 헤더(네이티브 앱) 또는 `cowork_ws_token` 쿠키(브라우저)를 검증합니다. 검증 후 REST와 같은 사용자 헤더를 주입하며, `WsOriginFilter`가 쿠키 자동 첨부를 악용한 WebSocket CSRF를 막기 위해 `Origin`을 `app.ws.allowed-origins`와 비교합니다.

현재 채팅은 Gateway path `/ws/chat`, Socket.IO namespace `/chat`을 사용합니다.

## 스택

- Spring Boot 4 / Kotlin / Java 25
- Spring Cloud Gateway, Eureka, Config Client, Config Bus(Kafka)
- Spring Security + JJWT
- Redis, Resilience4j

## 포트와 엔드포인트

- 포트: `8080`
- Health: `/actuator/health`
- Prometheus: `/actuator/prometheus`
- 통합 Swagger UI: `/swagger-ui.html`

라우트의 기준 파일은 `cowork-config/src/main/resources/configs/cowork-gateway-{profile}.yml`입니다.

## 주요 환경 변수

Compose는 Config Server 접속에 필요한 부트스트랩 값만 직접 주입합니다.

| 공급원        | 설정                                                        |
|---------------|-------------------------------------------------------------|
| Compose       | `SPRING_CONFIG_IMPORT`, `SPRING_PROFILES_ACTIVE`            |
| Config Server | 라우트, Redis, Kafka, Eureka, circuit breaker, Swagger 집계 |
| Vault         | `jwt.secret`                                                |

Compose에서는 Config Server 연결이 필수이며 조회에 실패하면 기동하지 않습니다.
