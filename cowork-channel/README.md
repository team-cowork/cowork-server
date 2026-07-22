# cowork-channel

## 역할

팀·프로젝트·DM 채널과 채널 부가 기능을 관리합니다.

- `TEXT`, `VOICE`, `DM` 채널 생성·조회·수정·삭제와 순서 변경
- 채널 멤버 관리와 1:1 DM 채널의 멱등 생성
- 채널 검색
- TEXT 채널 webhook과 메시지 thread 메타데이터
- 회의록과 회의록 template/section 관리
- `ACCOUNT_SHARE` 채널의 공유 계정·암호화된 credential 관리
- GitHub, Notion, Jira, Google, Facebook 계정 연결 OAuth
- 팀·사용자 lifecycle 이벤트에 따른 로컬 membership 정리

## 스택

- Spring Boot 4 / Kotlin / Java 25
- Spring Data JPA + MySQL + Flyway
- Spring Cloud Eureka·Config·OpenFeign
- Spring Kafka, Resilience4j

## 포트와 API

- 포트: `8083`
- 주요 경로: `/channels/**`, `/teams/{teamId}/channels`, `/projects/{projectId}/channels`, `/dms`, `/search/channels`
- OpenAPI / Swagger UI: `/v3/api-docs`, `/swagger-ui.html`
- Health / Prometheus: `/actuator/health`, `/actuator/prometheus`

## 이벤트와 의존성

- Kafka consume: `team.lifecycle`, `user.lifecycle`
- Kafka produce: `channel.event`, `channel.member.event`
- HTTP: `cowork-team`, `cowork-project`
- MySQL, Eureka, Config Server

## 환경 변수

| 공급원 | 설정 |
|---|---|
| Compose | `SPRING_CONFIG_IMPORT`, `SPRING_PROFILES_ACTIVE` |
| Config Server | 포트, MySQL URL, Kafka, Eureka, OAuth callback/redirect와 provider endpoint/scope |
| Vault | MySQL 계정, credential 암호화 키, OAuth state key, provider client ID/secret |

지원 provider는 GitHub, Notion, Jira, Google, Facebook입니다. 사용하지 않는 provider의 client 값은 비워둘 수 있습니다.
