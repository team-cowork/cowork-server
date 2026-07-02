# cowork-gateway

## 역할
API Gateway. 모든 외부 요청의 단일 진입점.
- JWT 검증 및 `X-User-Id`, `X-User-Role` 헤더 하위 서비스 전달
- Eureka 기반 로드밸런싱 (`lb://cowork-{service}`)
- Redis 기반 레이트리밋
- CORS 전역 설정

## `/chat-ws` 인증
브라우저의 WebSocket 핸드셰이크는 커스텀 `Authorization` 헤더를 실을 수 없어, 이 경로에 한해 예외적으로 쿠키(`cowork_ws_token`, `cowork-authorization`이 로그인/리프레시 시 발급)에서 JWT를 추출해 검증한다(`JwtServerAuthenticationConverter`). 검증 성공 시 다른 요청과 동일하게 `X-User-Id`/`X-User-Role` 헤더를 주입한다.
쿠키 자동 첨부를 악용한 웹소켓 CSRF를 막기 위해 `ChatWsOriginFilter`가 `Origin` 헤더를 `app.chat-ws.allowed-origins` 설정값과 대조한다(`Origin` 헤더가 없는 네이티브 앱 요청은 통과).

## 스택
- Spring Boot 4 / Kotlin / Java 25
- Spring Cloud Gateway (Reactive)
- Spring Security + JWT (jjwt)
- Redis (레이트리밋)

## 포트
`8080`

## 의존성
- Eureka Client, Config Client
