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
| HTTP | `8083` |

## 환경변수

운영 설정 입력은 [서비스 선언](../deploy/production/config/services.json)에 정의되어 있습니다.

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
