# cowork-channel

## 역할

팀·프로젝트·DM 채널과 채널 부가 기능을 관리합니다.

- 채널 생성·조회·수정·삭제, 순서 변경과 멤버 관리
- Webhook, 메시지 thread, 회의록과 템플릿 관리
- 공유 계정·암호화된 credential과 외부 계정 OAuth 연결
- 채널별 역할 권한 정책 조회·변경 요청

## 스택

- Kotlin / Java 25 / Spring Boot
- Gradle
- Spring Data JPA / MySQL / Flyway
- Spring Kafka / Eureka / Config Client

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
| --- | --- | --- |
| HTTP | `8083` | `8083` |

## 환경변수

아래 값은 [Docker Compose](../docker-compose.yml) 기준입니다.

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `local` | 설정 프로파일 (`local` 또는 `prod`) |
| `SPRING_CONFIG_IMPORT` | `configserver:http://cowork-config:8761` | 필수 Config Server 연결 |

- Config Server: 포트, MySQL URL, Kafka, Eureka, OAuth callback·redirect와 provider endpoint·scope.
- Vault: MySQL 계정, credential 암호화 키, OAuth state key, provider client ID·secret.

Compose 기동 시 Config Server 조회가 필수입니다. 일반 설정은 [서비스별 설정 파일](../cowork-config/src/main/resources/configs/), 시크릿 공급은 [설정 가이드](../docs/configuration.md)를 참고합니다.

OAuth provider는 GitHub, Notion, Jira, Google, Facebook이며 사용하지 않는 provider의 client 값은 비워둘 수 있습니다.
