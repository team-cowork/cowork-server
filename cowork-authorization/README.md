# cowork-authorization

## 역할

DataGSM 로그인과 인증 토큰·로그인 세션을 관리합니다.

- OAuth2 PKCE·OIDC 로그인과 cowork JWT 발급/갱신/로그아웃
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
| HTTP | `8081`        |

## 환경변수

설정은 환경변수와 secret 파일에서 읽습니다. 운영의 서비스 등록과 내부 통신 인증은 Nomad와 Consul이 담당합니다.

| 변수                                                          | 설명                                                                                                                                              |
|---------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| `PORT`                                                        | HTTP 포트                                                                                                                                         |
| `DB_HOST`                                                     | MySQL 호스트. 개별 DB 입력 5개를 모두 지정하거나 `DB_DSN` 사용                                                                                         |
| `DB_PORT`                                                     | MySQL 포트. 개별 DB 입력 5개를 모두 지정하거나 `DB_DSN` 사용                                                                                          |
| `DB_NAME`                                                     | MySQL 데이터베이스 이름. 개별 DB 입력 5개를 모두 지정하거나 `DB_DSN` 사용                                                                                   |
| `DB_USERNAME`                                                 | MySQL 사용자명. 개별 DB 입력 5개를 모두 지정하거나 `DB_DSN` 사용                                                                                        |
| `DB_PASSWORD`                                                 | MySQL 비밀번호. 개별 DB 입력 5개를 모두 지정하거나 `DB_DSN` 사용                                                                                        |
| `DB_DSN`                                                      | 개별 DB 입력이 없을 때 사용하는 MySQL DSN                                                                                                         |
| `DATAGSM_CLIENT_ID`                                           | OAuth client ID                                                                                                                               |
| `DATAGSM_REDIRECT_URIS`                                       | 필수. DataGSM에 등록된 웹·모바일·데스크탑 콜백 URL을 쉼표로 구분한 허용 목록. [전환·폐기 절차](docs/datagsm-migration.md) 참고 |
| `DATAGSM_AUTHORIZATION_BASE_URL`                              | 선택. 기본값 `https://oauth.authorization.datagsm.kr` |
| `DATAGSM_RESOURCE_BASE_URL`                                   | 선택. 기본값 `https://oauth.resource.datagsm.kr` |
| `JWT_SECRET`                                                  | JWT 서명 키                                                                                                                                      |
| `DATAGSM_WEBHOOK_SECRET`                                      | Gateway와 공유하는 DataGSM webhook 검증 값. 새 키를 적용한 Authorization은 이전 키로 서명된 대기 항목을 `invalid`로 분류해 로그·지표 기록 후 삭제 |
| `REDIS_HOST`                                                  | 로그인 시도·DataGSM webhook 대기열 Redis 접속 호스트. 운영에서는 Consul Envoy loopback 사용                                                                    |
| `REDIS_PORT`                                                  | 로그인 시도·DataGSM webhook 대기열 Redis 접속 포트. 운영에서는 Consul Envoy loopback 사용                                                                     |
| `JWT_ACCESS_EXPIRE`                                           | Access JWT 유효기간                                                                                                                               |
| `JWT_REFRESH_EXPIRE`                                          | Refresh JWT 유효기간                                                                                                                              |
| `KAFKA_BOOTSTRAP_SERVERS`                                     | Kafka broker 목록                                                                                                                                 |
| `KAFKA_SECURITY_PROTOCOL`                                     | 운영에서는 SASL_SSL                                                                                                                               |
| `KAFKA_SASL_MECHANISM`                                        | SCRAM-SHA-256 또는 SCRAM-SHA-512                                                                                                                  |
| `KAFKA_SASL_USERNAME`                                         | Kafka SASL 사용자명. SASL_SSL에서 필수                                                                                                          |
| `KAFKA_SASL_PASSWORD`                                         | Kafka SASL 비밀번호. SASL_SSL에서 필수                                                                                                          |
| `KAFKA_SSL_CA_LOCATION`                                       | Kafka CA PEM 파일 경로                                                                                                                            |
