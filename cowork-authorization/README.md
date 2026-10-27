# cowork-authorization

## 역할

DataGSM 로그인과 인증 토큰·로그인 세션을 관리합니다.

- OAuth2 PKCE 로그인과 JWT 발급·갱신·로그아웃
- 사용자 계정·프로필 소유 서비스인 cowork-user에 로그인 정보 동기화 요청
- DataGSM 사용자 변경 웹훅의 원자 접수·outbox 전달과 온라인·오프라인 상태 발행

## 스택

- Go / Gin
- Go modules + Makefile
- GORM / MySQL
- Kafka

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `8081`        | `8081`                   |

## 환경변수

설정은 환경변수와 secret 파일에서 읽습니다. 운영의 서비스 등록과 내부 통신 인증은 Nomad와 Consul이 담당합니다.

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `PORT` | `8081` | HTTP 포트 |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | 없음 | MySQL 접속 정보. 전부 지정하거나 DB_DSN 사용 |
| `DB_DSN` | 없음 | 개별 DB 입력이 없을 때 사용하는 MySQL DSN |
| `DATAGSM_CLIENT_ID`, `JWT_SECRET` | 없음 | 필수 OAuth client ID와 JWT 서명 키 |
| `DATAGSM_WEBHOOK_SECRET` | 없음 | DataGSM webhook 검증 값 |
| `JWT_ACCESS_EXPIRE`, `JWT_REFRESH_EXPIRE` | `30m`, `2160h` | JWT 유효기간 |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | Kafka broker 목록 |
| `KAFKA_SECURITY_PROTOCOL` | `PLAINTEXT` | 로컬은 PLAINTEXT, 운영은 SASL_SSL |
| `KAFKA_SASL_MECHANISM` | `SCRAM-SHA-512` | SCRAM-SHA-256 또는 SCRAM-SHA-512 |
| `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD` | 없음 | SASL_SSL에서 필수인 서비스 계정 |
| `KAFKA_SSL_CA_LOCATION` | 시스템 CA | Kafka CA PEM 파일 경로 |
