# cowork-gateway

## 역할

외부 HTTP·WebSocket 요청의 단일 진입점입니다.

- JWT 검증과 사용자 식별 헤더 전달
- Eureka 기반 서비스 라우팅과 전역 CORS 처리
- 요청 속도 제한, circuit breaker·retry와 공통 응답 처리
- 서비스별 OpenAPI를 모은 Swagger UI 제공

## 스택

- Kotlin / Java 25 / Spring Boot
- Gradle
- Spring Cloud Gateway / Eureka / Config Client / Config Bus (Kafka)
- Spring Security / JJWT / Redis / Resilience4j

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
| --- | --- | --- |
| HTTP / WebSocket | `8080` | `8080` |

## 환경변수

아래 값은 [Docker Compose](../docker-compose.yml) 기준입니다.

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `local` | 설정 프로파일 (`local` 또는 `prod`) |
| `SPRING_CONFIG_IMPORT` | `configserver:http://cowork-config:8761` | 필수 Config Server 연결 |

- Config Server: 포트, 라우트, CORS·WebSocket origin, Redis, Kafka, Eureka, circuit breaker, Swagger 집계.
- Vault: `jwt.secret`.

Compose 기동 시 Config Server 조회가 필수입니다. 일반 설정은 [서비스별 설정 파일](../cowork-config/src/main/resources/configs/), 시크릿 공급은 [설정 가이드](../docs/configuration.md)를 참고합니다.
