# cowork-gateway

## 역할

외부 HTTP·WebSocket 요청의 단일 진입점입니다.

- JWT 검증과 사용자 식별 헤더 전달
- 설정된 upstream으로 서비스 라우팅과 전역 CORS 처리
- 요청 속도 제한, circuit breaker·retry와 공통 응답 처리
- 서비스별 OpenAPI를 모은 Swagger UI 제공

## 스택

- Kotlin / Java 26 / Spring Boot
- Gradle
- Spring Cloud Gateway
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
| `COWORK_<SERVICE>_URL` | 서비스별 Compose HTTP 주소 | HTTP upstream. 운영에서는 Envoy loopback 주소 |
| `COWORK_CHAT_WS_URL` | `ws://cowork-chat:8087` | Chat WebSocket upstream |
| `JWT_SECRET` | 없음 | 사용자 JWT 검증 키 |
| `WEBHOOK_URL_SIGNING_KEY` | 없음 | Preference와 공유하는 웹훅 URL 서명 키 |
| `DEV_ORIGIN_CREDENTIAL` | 없음 | 허용된 개발 origin의 접근 자격증명 |
| `REDIS_HOST`, `REDIS_PORT` | `redis`, `6379` | 요청 제한 저장소 |
| `PUBLIC_WEB_ORIGINS` | 없음 | HTTP와 WebSocket에서 허용할 origin |
| `S3_TEAM_INTERNAL_ENDPOINT`, `S3_USER_INTERNAL_ENDPOINT`, `S3_CHAT_INTERNAL_ENDPOINT` | 로컬 SeaweedFS | 서비스별 파일 relay upstream |

웹훅 URL 서명 검증에는 `WEBHOOK_URL_SIGNING_KEY`를 사용합니다. Preference와 공유하는 32바이트 키의 Base64 문자열이며 `WEBHOOK_URL_SIGNING_KEY` 환경변수로 주입합니다. 키가 없거나 유효하지 않으면 웹훅 실행 요청은 503을 반환합니다. 키를 변경하면 발급된 URL이 무효화됩니다.
