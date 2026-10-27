# cowork-team

## 역할

팀·초대·멤버 권한과 팀 이미지를 관리합니다.

- 팀 생성·조회·수정·삭제와 멤버 초대·참여·제거
- 기본 멤버십 역할 관리와 사용자 정의 역할 조회·변경 요청
- 팀 아이콘 업로드와 GitHub App 설치 연결
- 팀·멤버 상태 변경과 사용자 알림 발행

## 스택

- Kotlin / Java 26 / Spring Boot
- Gradle
- Spring Data JPA / MySQL / Flyway
- Spring Kafka
- Spring Cloud AWS S3 / SeaweedFS (S3 호환)

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `8085`        | `8085`                   |

## 환경변수

아래 값은 [로컬 Compose 설정](../deploy/compose/stack.yaml)의 컨테이너 입력입니다.

| 변수                     | 기본값                      | 설명                                                  |
|--------------------------|-----------------------------|-------------------------------------------------------|
| `APP_CONFIG_PROFILE`     | `local`                     | 설정 프로파일                                         |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | 로컬 DB 설정 | 데이터베이스 접속 대상 |
| `DB_USERNAME`, `DB_PASSWORD` | 사용자 `cowork`, 비밀번호 없음 | 데이터베이스 자격증명 |
| `KAFKA_BOOTSTRAP_SERVERS` | 로컬 Kafka 설정 | Kafka broker 주소 |
| `KAFKA_SECURITY_PROTOCOL` | `PLAINTEXT` | 운영에서는 `SASL_SSL` |
| `KAFKA_SASL_MECHANISM` | `SCRAM-SHA-512` | SCRAM 인증 방식 |
| `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD` | 없음 | SASL 사용 시 필수 |
| `KAFKA_SSL_CA_LOCATION` | 없음 | Kafka CA PEM 파일 경로 |
