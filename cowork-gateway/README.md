# cowork-gateway

## 역할

외부 HTTP·WebSocket 요청의 단일 진입점입니다.

- JWT 검증과 사용자 식별 헤더 전달
- Eureka 기반 서비스 라우팅과 전역 CORS 처리
- 요청 속도 제한, circuit breaker·retry와 공통 응답 처리
- 서비스별 OpenAPI를 모은 Swagger UI 제공

## 패키지 구조

`com.cowork.gateway` 아래를 기능별로 나누고, 각 기능 안에서 설정·필터·모델 등의 역할을 구분합니다.

| 패키지 | 하위 패키지 | 역할 |
|--------|-------------|------|
| `security` | `config`, `jwt`, `authority`, `filter`, `websocket` | JWT 인증, 사용자 헤더 전달, WebSocket 보안 |
| `response` | `config`, `filter`, `model`, `metrics`, `wrapping`, `body`, `body.action` | 공통 응답 래핑, 크기 제한 버퍼링, 지표 수집 |
| `logging` | `config`, `filter` | 접근 로그 설정과 기록 |
| `ratelimit` | `config` | 요청 속도 제한 키 설정 |
| `health` | `controller`, `model` | 서비스 상태 조회와 대시보드 |
| `swagger` | `config`, `controller` | Swagger UI 설정과 제공 |
| `fallback` | `controller` | 서비스 장애 응답 |

클래스·인터페이스·enum·object는 타입명과 같은 이름의 파일에 하나씩 선언합니다. `main`과 상수는 관련 클래스 내부에 두며, 테스트도 대상 클래스와 같은 패키지 구조를 따릅니다.

## 스택

- Kotlin / Java 25 / Spring Boot
- Gradle
- Spring Cloud Gateway / Eureka / Config Client / Config Bus (Kafka)
- Spring Security / JJWT / Redis / Resilience4j

## 포트

| 용도             | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------------------|---------------|--------------------------|
| HTTP / WebSocket | `8080`        | `8080`                   |

## 환경변수

아래 값은 [Docker Compose](../docker-compose.yml) 기준입니다.

| 변수                     | 기본값                                   | 설명                                |
|--------------------------|------------------------------------------|-------------------------------------|
| `SPRING_PROFILES_ACTIVE` | `local`                                  | 설정 프로파일 (`local` 또는 `prod`) |
| `SPRING_CONFIG_IMPORT`   | `configserver:http://cowork-config:8761` | 필수 Config Server 연결             |

- Config Server: 포트, 라우트, CORS·WebSocket origin, Redis, Kafka, Eureka, circuit breaker, Swagger 집계.
- Vault: `jwt.secret`.

Compose 기동 시 Config Server 조회가 필수입니다. 일반 설정은 [서비스별 설정 파일](../cowork-config/src/main/resources/configs/), 시크릿 공급은 [설정 가이드](../docs/configuration.md)를 참고합니다.
