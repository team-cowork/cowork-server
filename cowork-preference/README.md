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
- Vert.x Redis Client / Kafka Client / Eureka / Config Server

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `9001`        | `9001`                   |

## 환경변수

아래 값은 [로컬 Compose 설정](../deploy/compose/stack.yaml)의 컨테이너 입력입니다.

| 변수                     | 기본값                      | 설명                                                        |
|--------------------------|-----------------------------|-------------------------------------------------------------|
| `APP_CONFIG_PROFILE`     | `local`                     | 설정 프로파일                                               |
| `CONFIG_SERVER_URL`      | `http://cowork-config:8761` | 필수 Config Server 연결                                     |
| `CONFIG_CLIENT_USERNAME` | 없음                        | 필수. `.env`의 `CONFIG_CLIENT_PREFERENCE_USERNAME`에서 전달 |
| `CONFIG_CLIENT_PASSWORD` | 없음                        | 필수. `.env`의 `CONFIG_CLIENT_PREFERENCE_PASSWORD`에서 전달 |

웹훅 비밀키 발급 응답의 재생에는 Config/Vault의 `preference.webhook.secret-encryption-key`를 사용합니다. 값은 32바이트 키의 Base64 문자열이며 `WEBHOOK_SECRET_ENCRYPTION_KEY` 환경변수로 재정의할 수 있습니다. 키가 없으면 비밀키 발급 요청은 503을 반환합니다.

웹훅 URL 서명에는 Config/Vault의 `preference.webhook.url-signing-key`를 사용합니다. Gateway와 공유하는 별도의 32바이트 키의 Base64 문자열이며 `WEBHOOK_URL_SIGNING_KEY` 환경변수로 재정의할 수 있습니다. 키를 변경하면 발급된 URL이 무효화됩니다.
