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

| 용도 | 컨테이너 포트 |
|------|---------------|
| HTTP | `8088` |

## 환경변수

운영 설정 입력은 [서비스 선언](../deploy/production/services.json)에 정의되어 있습니다.

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
