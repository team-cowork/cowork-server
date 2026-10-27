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

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `8083`        | `8083`                   |

## 환경변수

아래 값은 [로컬 Compose 설정](../deploy/compose/stack.yaml)의 컨테이너 입력입니다.

| 변수                     | 기본값                      | 설명                                                     |
|--------------------------|-----------------------------|----------------------------------------------------------|
| `APP_CONFIG_PROFILE`     | `local`                     | 설정 프로파일                                            |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | 로컬 DB 설정 | 데이터베이스 접속 대상 |
| `DB_USERNAME`, `DB_PASSWORD` | 사용자 `cowork`, 비밀번호 없음 | 데이터베이스 자격증명 |
| `KAFKA_BOOTSTRAP_SERVERS` | 로컬 Kafka 설정 | Kafka broker 주소 |
| `KAFKA_SECURITY_PROTOCOL` | `PLAINTEXT` | 운영에서는 `SASL_SSL` |
| `KAFKA_SASL_MECHANISM` | `SCRAM-SHA-512` | SCRAM 인증 방식 |
| `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD` | 없음 | SASL 사용 시 필수 |
| `KAFKA_SSL_CA_LOCATION` | 없음 | Kafka CA PEM 파일 경로 |
