# cowork-project

## 역할

팀 내 프로젝트·멤버 권한과 GitHub 저장소 연동을 관리합니다.

- 프로젝트 생성·조회·수정·삭제와 순서 변경
- 팀 기본·사용자 지정 역할과 프로젝트별 정책에 따른 접근 권한 검증
- GitHub 저장소 연결, PR 조회·승인·병합과 라벨 정책 변경 요청

## 스택

- Kotlin / Java 26 / Spring Boot
- Maven
- Spring Data JPA / MySQL / Flyway
- Spring Kafka / OpenFeign

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `8084`        | `8084`                   |

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
| `COWORK_GITHUB_URL` | `http://cowork-github:8090` | 요청 시점 GitHub 상태 조회 upstream |
