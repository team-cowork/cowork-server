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

| 변수                     | 기본값                      | 설명                                                     |
|--------------------------|-----------------------------|----------------------------------------------------------|
| `APP_CONFIG_PROFILE`     | `local`                     | 설정 프로파일                                            |
| `CONFIG_SERVER_URL`      | `http://cowork-config:8761` | 필수 Config Server 연결                                  |
| `CONFIG_CLIENT_USERNAME` | 없음                        | 필수. `.env`의 `CONFIG_CLIENT_CHANNEL_USERNAME`에서 전달 |
| `CONFIG_CLIENT_PASSWORD` | 없음                        | 필수. `.env`의 `CONFIG_CLIENT_CHANNEL_PASSWORD`에서 전달 |
