# cowork-channel

## 역할

팀·프로젝트·DM 채널과 채널 부가 기능을 관리합니다.

- 채널 생성·조회·수정·삭제, 순서 변경과 멤버 관리
- Webhook, 메시지 thread, 회의록과 템플릿 관리
- 공유 계정·암호화된 credential과 외부 계정 OAuth 연결
- 채널별 역할 권한 정책 조회·변경 요청

## 스택

- Kotlin / Java 26 / Spring Boot
- Gradle
- Spring Data JPA / MySQL / Flyway
- Spring Kafka / Eureka / Config Client

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `8083`        | `8083`                   |

## 환경변수

아래 값은 [로컬 Compose 설정](../deploy/compose/stack.yaml)의 컨테이너 입력입니다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | 설정 프로파일 |
| `CONFIG_SERVER_URL` | `http://cowork-config:8761` | 필수 Config Server 연결 |
| `CONFIG_CLIENT_USERNAME` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_CHANNEL_USERNAME`에서 전달 |
| `CONFIG_CLIENT_PASSWORD` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_CHANNEL_PASSWORD`에서 전달 |

기동 시 인증된 Config 조회가 필요합니다. 일반 설정은 [서비스별 설정](../cowork-config/src/main/resources/configs/),
앱 비밀 값은 Vault에서 공급합니다.

운영 포트·프로파일·환경변수·비밀 참조는 [channel 슬롯](../deploy/prod/slots/channel.json)에서 관리합니다.
[이미지 bootstrap](deploy/bootstrap.sh)이 공통 입력을 이 런타임의 환경변수로 변환합니다.

[로컬 실행](../docs/local-run-guide.md) · [운영 배포](../docs/deployment.md) · [Config 접근 보호](../docs/config-server-access.md)

사용하지 않는 OAuth provider의 client 값은 비워 둘 수 있습니다.
