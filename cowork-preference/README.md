# cowork-preference

## 역할

사용자·팀·채널의 설정과 사용자 정의 역할 정책을 전담 관리합니다.

- 사용자 상태·표시 설정, 팀·채널 설정과 채널별 알림 설정
- 프로젝트 역할과 팀 사용자 정의 역할·권한·멤버 할당
- 역할 기본 권한, 채널별 역할 권한 덮어쓰기 정책과 GitHub 저장소 라벨 정책
- 채널 웹훅 설정·비밀키 해시 소유, 보안 모드 변경과 Kafka 상태 발행
- 설정 캐시·상태 만료 처리와 변경 이벤트 발행

## 스택

- Kotlin / Java 26 / Vert.x Coroutines
- Kotlin Toolchain
- Vert.x PostgreSQL Client / PostgreSQL / Flyway
- Vert.x Redis Client / Kafka Client

## 포트

| 용도 | 컨테이너 포트 |
|------|---------------|
| HTTP | `9001` |

## 환경변수

운영 설정 입력은 [서비스 선언](../deploy/production/services.json)에 정의되어 있습니다.

| 변수 | 설명 |
|------|------|
| `APP_CONFIG_PROFILE` | 설정 프로파일 |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | 데이터베이스 접속 대상 |
| `DB_USERNAME`, `DB_PASSWORD` | 데이터베이스 자격증명 |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker 주소 |
| `KAFKA_SECURITY_PROTOCOL` | 운영에서는 `SASL_SSL` |
| `KAFKA_SASL_MECHANISM` | SCRAM 인증 방식 |
| `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD` | SASL 사용 시 필수 |
| `KAFKA_SSL_CA_LOCATION` | Kafka CA PEM 파일 경로 |
| `REDIS_HOST`, `REDIS_PORT` | 설정 캐시 |
| `WEBHOOK_SECRET_ENCRYPTION_KEY`, `WEBHOOK_URL_SIGNING_KEY` | 웹훅 비밀키 재생과 URL 서명 키 |

웹훅 비밀키 발급 응답의 재생에는 `WEBHOOK_SECRET_ENCRYPTION_KEY`를 사용합니다. 값은 32바이트 키의 Base64 문자열이며 `WEBHOOK_SECRET_ENCRYPTION_KEY` 환경변수로 주입합니다. 키가 없으면 비밀키 발급 요청은 503을 반환합니다.

웹훅 URL 서명에는 `WEBHOOK_URL_SIGNING_KEY`를 사용합니다. Gateway와 공유하는 별도의 32바이트 키의 Base64 문자열이며 `WEBHOOK_URL_SIGNING_KEY` 환경변수로 주입합니다. 키를 변경하면 발급된 URL이 무효화됩니다.
