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

| 변수                     | 기본값                      | 설명                                                     |
|--------------------------|-----------------------------|----------------------------------------------------------|
| `APP_CONFIG_PROFILE`     | `local`                     | 설정 프로파일                                            |
| `CONFIG_SERVER_URL`      | `http://cowork-config:8761` | 필수 Config Server 연결                                  |
| `CONFIG_CLIENT_USERNAME` | 없음                        | 필수. `.env`의 `CONFIG_CLIENT_GATEWAY_USERNAME`에서 전달 |
| `CONFIG_CLIENT_PASSWORD` | 없음                        | 필수. `.env`의 `CONFIG_CLIENT_GATEWAY_PASSWORD`에서 전달 |

웹훅 URL 서명 검증에는 Config/Vault의 `webhook.url-signing-key`를 사용합니다. Preference와 공유하는 32바이트 키의 Base64 문자열이며 `WEBHOOK_URL_SIGNING_KEY` 환경변수로 재정의할 수 있습니다. 키가 없거나 유효하지 않으면 웹훅 실행 요청은 503을 반환합니다. 키를 변경하면 발급된 URL이 무효화됩니다.
