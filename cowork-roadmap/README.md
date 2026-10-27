# cowork-roadmap

## 역할

전공·포지션별 온보딩 로드맵과 학습 과제를 관리합니다.

- 공통·팀·프로젝트 범위의 로드맵 생성·조회·수정·삭제
- 트리형 노드와 문서·관련 자료 관리
- 멤버별 과제 출제와 진행 상태 관리

## 스택

- Java 26 / Spring Boot MVC
- Gradle
- Spring Data JPA / MySQL / Flyway
- Spring Kafka

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `8088`        | `8088`                   |

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
