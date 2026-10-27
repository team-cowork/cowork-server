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

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `8089`        | `8089`                   |

## 환경변수

설정은 환경변수와 secret 파일에서 읽습니다. 운영의 서비스 등록과 내부 통신 인증은 Nomad와 Consul이 담당합니다.

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `PORT` | `8089` | HTTP 포트 |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | 없음 | MongoDB 접속 정보. 전부 지정하거나 URI 입력 사용 |
| `MONGODB_URI`, `MONGODB_DB` | 없음 | 개별 DB 입력이 없을 때 필수인 MongoDB URI와 DB명 |
| `LIVEKIT_URL`, `LIVEKIT_WS_URL` | 없음 | 필수 내부 API와 클라이언트 공개 WebSocket 주소 |
| `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` | 없음 | 필수 LiveKit 자격증명 |
| `LIVEKIT_TOKEN_TTL_SECS` | `3600` | LiveKit 토큰 유효기간 |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | Kafka broker 목록 |
| `KAFKA_SECURITY_PROTOCOL` | `PLAINTEXT` | 로컬은 PLAINTEXT, 운영은 SASL_SSL |
| `KAFKA_SASL_MECHANISM` | `SCRAM-SHA-512` | SCRAM-SHA-256 또는 SCRAM-SHA-512 |
| `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD` | 없음 | SASL_SSL에서 필수인 서비스 계정 |
| `KAFKA_SSL_CA_LOCATION` | 시스템 CA | Kafka CA PEM 파일 경로 |
