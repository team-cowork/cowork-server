# cowork-gateway

## 역할

외부 HTTP·WebSocket 요청의 단일 진입점입니다.

- JWT 검증과 사용자 식별 헤더 전달
- 설정된 upstream으로 서비스 라우팅과 전역 CORS 처리
- 요청 속도 제한, circuit breaker·retry와 공통 응답 처리
- 배포 중인 서비스의 HTTP 요청 안내와 DataGSM 웹훅 영속 접수
- 서비스별 OpenAPI를 모은 Swagger UI 제공

## 스택

- Kotlin / Java 26 / Spring Boot
- Gradle
- Spring Cloud Gateway
- Spring Security / JJWT / Redis / Resilience4j

## 포트

| 용도 | 컨테이너 포트 |
|------|---------------|
| HTTP / WebSocket | `8080` |

## 환경변수

운영 설정 입력은 [서비스 선언](../deploy/production/services.json)에 정의되어 있습니다.

| 변수 | 설명 |
|------|------|
| `APP_CONFIG_PROFILE` | 설정 프로파일 |
| `COWORK_<SERVICE>_URL` | HTTP upstream. 운영에서는 Envoy loopback 주소 |
| `COWORK_CHAT_WS_URL` | Chat WebSocket upstream |
| `JWT_SECRET` | 사용자 JWT 검증 키 |
| `WEBHOOK_URL_SIGNING_KEY` | Preference와 공유하는 웹훅 URL 서명 키 |
| `DATAGSM_WEBHOOK_SECRET` | DataGSM 웹훅 HMAC 검증 키 |
| `DEV_ORIGIN_CREDENTIAL` | 허용된 개발 origin의 접근 자격증명 |
| `REDIS_HOST`, `REDIS_PORT` | 요청 제한, 배포 상태, DataGSM 웹훅 접수 저장소 |
| `PUBLIC_WEB_ORIGINS` | HTTP와 WebSocket에서 허용할 origin |
| `S3_TEAM_INTERNAL_ENDPOINT`, `S3_USER_INTERNAL_ENDPOINT`, `S3_CHAT_INTERNAL_ENDPOINT` | 서비스별 파일 relay upstream |

웹훅 URL 서명 검증에는 `WEBHOOK_URL_SIGNING_KEY`를 사용합니다. Preference와 공유하는 32바이트 키의 Base64 문자열이며 `WEBHOOK_URL_SIGNING_KEY` 환경변수로 주입합니다. 키가 없거나 유효하지 않으면 웹훅 실행 요청은 503을 반환합니다. 키를 변경하면 발급된 URL이 무효화됩니다.
