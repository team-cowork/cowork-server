# cowork-gateway

## 역할
API Gateway. 모든 외부 요청의 단일 진입점.
- JWT 검증 및 `X-User-Id`, `X-User-Role` 헤더 하위 서비스 전달
- Eureka 기반 로드밸런싱 (`lb://cowork-{service}`)
- Redis 기반 레이트리밋
- CORS 전역 설정

## `/chat-ws` 인증
브라우저의 WebSocket 핸드셰이크는 커스텀 `Authorization` 헤더를 실을 수 없어, `/chat-ws`는 기본 보안 체인과 분리된 전용 `SecurityWebFilterChain`(`SecurityConfig.chatWsSecurityWebFilterChain`)에서 별도로 처리한다. 이 체인은 `ChatWsJwtServerAuthenticationConverter`로 `Authorization` 헤더(네이티브 앱) 또는 쿠키(`cowork_ws_token`, `cowork-authorization`이 로그인/리프레시 시 발급, 브라우저)에서 JWT를 추출해 검증하고, 검증 성공 시 다른 요청과 동일하게 `X-User-Id`/`X-User-Role` 헤더를 주입한다. 기본 체인의 `JwtServerAuthenticationConverter`는 이 예외를 몰라도 되도록 `Authorization` 헤더만 처리하는 단순한 형태로 유지한다.
쿠키 자동 첨부를 악용한 웹소켓 CSRF를 막기 위해 같은 체인에 연결된 `ChatWsOriginFilter`가 `Origin` 헤더를 `app.chat-ws.allowed-origins` 설정값과 대조한다(`Origin` 헤더가 없는 네이티브 앱 요청은 통과).

## 스택
- Spring Boot 4 / Kotlin / Java 25
- Spring Cloud Gateway (Reactive)
- Spring Security + JWT (jjwt)
- Redis (레이트리밋)

## 포트
`8080`

## 의존성
- Eureka Client, Config Client
