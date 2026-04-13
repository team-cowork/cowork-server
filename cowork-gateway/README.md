# cowork-gateway

## 역할
API Gateway. 모든 외부 요청의 단일 진입점.

- JWT 토큰 검증 및 `X-User-Id`, `X-User-Role` 헤더 하위 서비스 전달
- Eureka 기반 로드밸런싱 (`lb://cowork-{service}`)
- CORS 전역 설정
- 라우팅 규칙 관리

## 스택
Spring Boot + Spring Cloud Gateway (Reactive)

## 포트
`8080`

## 의존성
- Spring Cloud Gateway
- Spring Cloud Netflix Eureka Client
- Spring Cloud Config Client
