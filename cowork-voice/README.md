# cowork-voice

## 역할

LiveKit 기반 음성 채널과 1:N 라이브 룸을 관리합니다.

- 음성·라이브 룸 참가·퇴장, 참가자와 세션 관리
- LiveKit access token 발급과 참가 권한 확인
- LiveKit webhook 상태 동기화와 음성·라이브 이벤트 발행

## 스택

- Go / Chi
- Go modules + Makefile
- LiveKit Server SDK / MongoDB
- Kafka

## 포트

| 용도 | 컨테이너 포트 |
|------|---------------|
| HTTP | `8089`        |

## 환경변수

설정은 환경변수와 secret 파일에서 읽습니다. 운영의 서비스 등록과 내부 통신 인증은 Nomad와 Consul이 담당합니다.

| 변수                                                          | 설명                                             |
|---------------------------------------------------------------|--------------------------------------------------|
| `PORT`                                                        | HTTP 포트                                        |
| `DB_HOST`                                                     | MongoDB 호스트. 개별 DB 입력 5개를 모두 지정하거나 `MONGODB_URI`와 `MONGODB_DB` 사용 |
| `DB_PORT`                                                     | MongoDB 포트. 개별 DB 입력 5개를 모두 지정하거나 `MONGODB_URI`와 `MONGODB_DB` 사용 |
| `DB_NAME`                                                     | MongoDB 데이터베이스 이름. 개별 DB 입력 5개를 모두 지정하거나 `MONGODB_URI`와 `MONGODB_DB` 사용 |
| `DB_USERNAME`                                                 | MongoDB 사용자명. 개별 DB 입력 5개를 모두 지정하거나 `MONGODB_URI`와 `MONGODB_DB` 사용 |
| `DB_PASSWORD`                                                 | MongoDB 비밀번호. 개별 DB 입력 5개를 모두 지정하거나 `MONGODB_URI`와 `MONGODB_DB` 사용 |
| `MONGODB_URI`                                                 | 개별 DB 입력이 없을 때 필수인 MongoDB URI      |
| `MONGODB_DB`                                                  | 개별 DB 입력이 없을 때 필수인 MongoDB 데이터베이스 이름 |
| `LIVEKIT_URL`                                                 | 필수 LiveKit 내부 API 주소               |
| `LIVEKIT_WS_URL`                                              | 필수 LiveKit 클라이언트 공개 WebSocket 주소   |
| `LIVEKIT_API_KEY`                                             | 필수 LiveKit API 키                           |
| `LIVEKIT_API_SECRET`                                          | 필수 LiveKit API 비밀키                         |
| `LIVEKIT_TOKEN_TTL_SECS`                                      | LiveKit 토큰 유효기간                            |
| `KAFKA_BOOTSTRAP_SERVERS`                                     | Kafka broker 목록                                |
| `KAFKA_SECURITY_PROTOCOL`                                     | 운영에서는 SASL_SSL                              |
| `KAFKA_SASL_MECHANISM`                                        | SCRAM-SHA-256 또는 SCRAM-SHA-512                 |
| `KAFKA_SASL_USERNAME`                                         | Kafka SASL 사용자명. SASL_SSL에서 필수         |
| `KAFKA_SASL_PASSWORD`                                         | Kafka SASL 비밀번호. SASL_SSL에서 필수         |
| `KAFKA_SSL_CA_LOCATION`                                       | Kafka CA PEM 파일 경로                           |
