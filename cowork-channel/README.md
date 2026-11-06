# cowork-channel

## 역할

팀·프로젝트·DM 채널과 채널 부가 기능을 관리합니다.

- 채널 생성·조회·수정·삭제, 순서 변경과 멤버 관리
- 메시지 thread, 회의록과 템플릿 관리
- 기존 웹훅 데이터를 Kafka로 cowork-preference에 이관
- 공유 계정·암호화된 credential과 외부 계정 OAuth 연결
- 채널별 역할 권한 정책 조회·변경 요청

## 스택

- Kotlin / Java 26 / Spring Boot
- Gradle
- Spring Data JPA / MySQL / Flyway
- Spring Kafka

## 포트

| 용도 | 컨테이너 포트 |
|------|---------------|
| HTTP | `8083`        |

## 환경변수

운영 설정 입력은 [서비스 선언](../deploy/production/config/services.json)에 정의되어 있습니다.

| 변수                                                                                 | 설명                          |
|--------------------------------------------------------------------------------------|-------------------------------|
| `APP_CONFIG_PROFILE`                                                                 | 설정 프로파일                 |
| `DB_HOST`                                                                            | 데이터베이스 호스트          |
| `DB_PORT`                                                                            | 데이터베이스 포트           |
| `DB_NAME`                                                                            | 데이터베이스 이름           |
| `DB_USERNAME`                                                                        | 데이터베이스 사용자명         |
| `DB_PASSWORD`                                                                        | 데이터베이스 비밀번호         |
| `KAFKA_BOOTSTRAP_SERVERS`                                                            | Kafka broker 주소             |
| `KAFKA_SECURITY_PROTOCOL`                                                            | 운영에서는 `SASL_SSL`         |
| `KAFKA_SASL_MECHANISM`                                                               | SCRAM 인증 방식               |
| `KAFKA_SASL_USERNAME`                                                                | Kafka SASL 사용자명. SASL 사용 시 필수 |
| `KAFKA_SASL_PASSWORD`                                                                | Kafka SASL 비밀번호. SASL 사용 시 필수 |
| `KAFKA_SSL_CA_LOCATION`                                                              | Kafka CA PEM 파일 경로        |
| `ACCOUNT_CREDENTIAL_ENCRYPTION_KEY`                                                  | credential 암호화 키          |
| `ACCOUNT_SHARE_OAUTH_STATE_SECRET`                                                   | OAuth state 서명 키           |
| `PUBLIC_API_BASE_URL`                                                                | OAuth 콜백의 기준 API 주소    |
| `PUBLIC_WEB_ORIGINS`                                                                 | OAuth 인증 후 복귀를 허용할 origin 목록 |
| `COWORK_GITHUB_ACCOUNT_SHARE_CLIENT_ID`                                              | GitHub OAuth client ID    |
| `COWORK_GITHUB_ACCOUNT_SHARE_CLIENT_SECRET`                                          | GitHub OAuth client secret |
| `NOTION_ACCOUNT_SHARE_CLIENT_ID`                                                     | Notion OAuth client ID    |
| `NOTION_ACCOUNT_SHARE_CLIENT_SECRET`                                                 | Notion OAuth client secret |
| `JIRA_ACCOUNT_SHARE_CLIENT_ID`                                                       | Jira OAuth client ID      |
| `JIRA_ACCOUNT_SHARE_CLIENT_SECRET`                                                   | Jira OAuth client secret  |
| `GOOGLE_ACCOUNT_SHARE_CLIENT_ID`                                                     | Google OAuth client ID    |
| `GOOGLE_ACCOUNT_SHARE_CLIENT_SECRET`                                                 | Google OAuth client secret |
| `FACEBOOK_ACCOUNT_SHARE_CLIENT_ID`                                                   | Facebook OAuth client ID  |
| `FACEBOOK_ACCOUNT_SHARE_CLIENT_SECRET`                                               | Facebook OAuth client secret |
