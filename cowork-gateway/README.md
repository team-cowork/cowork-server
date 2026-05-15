# cowork-gateway

## 역할
API Gateway. 모든 외부 요청의 단일 진입점.
- JWT 검증 및 `X-User-Id`, `X-User-Role` 헤더 하위 서비스 전달
- Eureka 기반 로드밸런싱 (`lb://cowork-{service}`)
- Redis 기반 레이트리밋
- CORS 전역 설정

## 스택
- Spring Boot 3 / Kotlin / Java 21
- Spring Cloud Gateway (Reactive)
- Spring Security + JWT (jjwt)
- Redis (레이트리밋)

## 포트
`8080`

## 의존성
- Eureka Client, Config Client
