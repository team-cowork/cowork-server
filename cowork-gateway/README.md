# cowork-gateway

## 역할

외부 HTTP·WebSocket 요청의 단일 진입점입니다.

- JWT 검증과 사용자 식별 헤더 전달
- Eureka 기반 서비스 라우팅과 전역 CORS 처리
- 요청 속도 제한, circuit breaker·retry와 공통 응답 처리
- 서비스별 OpenAPI를 모은 Swagger UI 제공

## 스택

- Kotlin / Java 26 / Spring Boot
- Gradle
- Spring Cloud Gateway / Eureka / Config Client / Config Bus (Kafka)
- Spring Security / JJWT / Redis / Resilience4j

## 포트

| 용도             | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------------------|---------------|--------------------------|
| HTTP / WebSocket | `8080`        | `8080`                   |

## 환경변수

아래 값은 [로컬 Compose 설정](../deploy/compose/stack.yaml)의 컨테이너 입력입니다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | 설정 프로파일 |
| `CONFIG_SERVER_URL` | `http://cowork-config:8761` | 필수 Config Server 연결 |
| `CONFIG_CLIENT_USERNAME` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_GATEWAY_USERNAME`에서 전달 |
| `CONFIG_CLIENT_PASSWORD` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_GATEWAY_PASSWORD`에서 전달 |

기동 시 인증된 Config 조회가 필요합니다. 일반 설정은 [서비스별 설정](../cowork-config/src/main/resources/configs/),
앱 비밀 값은 Vault에서 공급합니다.

운영 포트·프로파일·환경변수·비밀 참조는 [gateway 슬롯](../deploy/prod/slots/gateway.json)에서 관리합니다.

[로컬 실행](../docs/local-run-guide.md) · [운영 배포](../docs/deployment.md) · [Config 접근 보호](../docs/config-server-access.md)
