# cowork-authorization

## 역할

DataGSM 로그인과 인증 토큰·로그인 세션을 관리합니다.

- OAuth2 PKCE 로그인과 JWT 발급·갱신·로그아웃
- 사용자 계정·프로필 소유 서비스인 cowork-user에 로그인 정보 동기화 요청
- DataGSM 사용자 변경 웹훅의 원자 접수·outbox 전달, Redis 대기열 소비와 온라인·오프라인 상태 발행

## 스택

- Go / Gin
- Go modules + Makefile
- GORM / MySQL
- Kafka
- Redis Streams

## 포트

| 용도 | 컨테이너 포트 |
|------|---------------|
| HTTP | `8081` |

## 환경변수

설정은 환경변수와 secret 파일에서 읽습니다. 운영의 서비스 등록과 내부 통신 인증은 Nomad와 Consul이 담당합니다.

| 변수 | 설명 |
|------|------|
| `PORT` | HTTP 포트 |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | MySQL 접속 정보. 전부 지정하거나 DB_DSN 사용 |
| `DB_DSN` | 개별 DB 입력이 없을 때 사용하는 MySQL DSN |
| `DATAGSM_CLIENT_ID`, `JWT_SECRET` | 필수 OAuth client ID와 JWT 서명 키 |
| `DATAGSM_WEBHOOK_SECRET` | DataGSM webhook 검증 값 |
| `REDIS_HOST`, `REDIS_PORT` | DataGSM webhook 대기열 접속 주소. 운영에서는 Consul Envoy loopback 사용 |
| `JWT_ACCESS_EXPIRE`, `JWT_REFRESH_EXPIRE` | JWT 유효기간 |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker 목록 |
| `KAFKA_SECURITY_PROTOCOL` | 운영에서는 SASL_SSL |
| `KAFKA_SASL_MECHANISM` | SCRAM-SHA-256 또는 SCRAM-SHA-512 |
| `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD` | SASL_SSL에서 필수인 서비스 계정 |
| `KAFKA_SSL_CA_LOCATION` | Kafka CA PEM 파일 경로 |
