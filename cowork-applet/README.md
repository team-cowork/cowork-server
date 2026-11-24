# cowork-applet

## 역할

사용자가 서비스 안에서 직접 만드는 앱(applet)을 전담 관리합니다.

## 스택

- Scala 3 / Java 25 / Play Framework
- sbt
- SurrealDB
- Micrometer Prometheus

## 포트

| 용도 | 컨테이너 포트 |
|------|---------------|
| HTTP | `8091`        |

## 환경변수

운영 설정 입력은 [서비스 선언](../deploy/production/config/services.json)에 정의되어 있습니다.

| 변수                             | 설명                             |
|--------------------------------|--------------------------------|
| `APPLET_SECRET_KEY`            | Play 애플리케이션 비밀키                |
| `APPLET_SECRET_ENCRYPTION_KEY` | 앱 시크릿 암호화 키. Base64 인코딩한 32바이트 |
| `DB_HOST`                      | SurrealDB 호스트                  |
| `DB_PORT`                      | SurrealDB HTTP 포트              |
| `DB_NAME`                      | SurrealDB database 이름          |
| `DB_USERNAME`                  | SurrealDB database 사용자         |
| `DB_PASSWORD`                  | SurrealDB 사용자 비밀번호             |
| `KAFKA_BOOTSTRAP_SERVERS`      | Kafka broker 주소                |
| `KAFKA_SECURITY_PROTOCOL`      | 운영에서는 `SASL_SSL`               |
| `KAFKA_SASL_MECHANISM`         | SCRAM 인증 방식                    |
| `KAFKA_SASL_USERNAME`          | Kafka SASL 사용자명. SASL 사용 시 필수  |
| `KAFKA_SASL_PASSWORD`          | Kafka SASL 비밀번호. SASL 사용 시 필수  |
| `KAFKA_SSL_CA_LOCATION`        | Kafka CA PEM 파일 경로             |
