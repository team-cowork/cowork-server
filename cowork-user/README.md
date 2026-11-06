# cowork-user

## 역할

사용자 계정·공개 프로필의 원본 데이터를 관리합니다.

- 로그인 요청에 따른 계정·프로필 생성·동기화와 DataGSM 정보 갱신
- 프로필 조회·수정, 사용자 검색과 상태 메시지 관리
- 프로필 이미지 업로드·삭제와 온라인·오프라인 상태 반영

## 스택

- C# / ASP.NET Core (.NET 10)
- .NET CLI (`dotnet test cowork-user.slnx`)
- MySqlConnector · Dapper / MySQL (마이그레이션은 공식 Flyway CLI)
- Confluent.Kafka / StackExchange.Redis / AWS SDK S3 / SeaweedFS

## 포트

| 용도 | 컨테이너 포트 |
|------|---------------|
| HTTP | `8082`        |

## 환경변수

설정은 환경변수와 secret 파일에서 읽습니다. 운영의 서비스 등록과 내부 통신 인증은 Nomad와 Consul이 담당합니다.

| 변수                                                          | 설명                                          |
|---------------------------------------------------------------|-----------------------------------------------|
| `PORT`                                                        | HTTP 포트                                     |
| `APP_CONFIG_PROFILE`                                          | prod에서는 공개 API origin의 HTTPS 검증 적용  |
| `DB_HOST`                                                     | 필수 MySQL 호스트                             |
| `DB_PORT`                                                     | 필수 MySQL 포트                               |
| `DB_NAME`                                                     | 필수 MySQL 데이터베이스 이름                  |
| `DB_USERNAME`                                                 | 필수 MySQL 사용자명                           |
| `DB_PASSWORD`                                                 | 필수 MySQL 비밀번호                           |
| `REDIS_HOST`                                                  | Redis 접속 호스트                             |
| `REDIS_PORT`                                                  | Redis 접속 포트                               |
| `S3_INTERNAL_ENDPOINT`                                        | S3 API 주소                                   |
| `S3_ACCESS_KEY`                                               | 필수 서비스별 S3 액세스 키                    |
| `S3_SECRET_KEY`                                               | 필수 서비스별 S3 비밀키                       |
| `S3_BUCKET`                                                   | S3 bucket 이름                                |
| `S3_REGION`                                                   | S3 서명 region                                |
| `PUBLIC_API_BASE_URL`                                         | Gateway의 공개 origin. prod는 외부 HTTPS 주소 |
| `KAFKA_BOOTSTRAP_SERVERS`                                     | Kafka broker 목록                             |
| `KAFKA_SECURITY_PROTOCOL`                                     | 운영에서는 SASL_SSL                           |
| `KAFKA_SASL_MECHANISM`                                        | SCRAM-SHA-256 또는 SCRAM-SHA-512              |
| `KAFKA_SASL_USERNAME`                                         | Kafka SASL 사용자명. SASL_SSL에서 필수        |
| `KAFKA_SASL_PASSWORD`                                         | Kafka SASL 비밀번호. SASL_SSL에서 필수        |
| `KAFKA_SSL_CA_LOCATION`                                       | Kafka CA PEM 파일 경로                        |
