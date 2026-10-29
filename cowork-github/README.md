# cowork-github

## 역할

GitHub App으로 조직 저장소의 이슈·PR·라벨을 다루고 GitHub 이벤트를 cowork로 전달합니다.

- 채팅 명령으로 들어온 이슈 생성과 PR 머지·승인 처리
- 라벨 교체와 이슈 댓글 작성·수정·삭제 커맨드 처리와 결과 발행
- 저장소·이슈·댓글·라벨·PR 조회 내부 HTTP API
- GitHub App 설치·해제 연결과 저장소 Webhook 이벤트 발행

## 스택

- TypeScript / Node.js / NestJS
- npm / Nest CLI
- KafkaJS / Redis (ioredis)
- GitHub App (JWT · installation access token)

## 포트

| 용도 | 컨테이너 포트 |
|------|---------------|
| HTTP | `8090` |

## 환경변수

설정은 환경변수로 직접 전달합니다. 운영에서는 Nomad가 비밀 값과 환경변수를 주입하고 서비스 등록을 관리합니다.

| 변수 | 설명 |
|------|------|
| `PORT` | HTTP 포트 |
| `NODE_ENV` | 운영은 `production` 지정 |
| `KAFKA_BOOTSTRAP_SERVERS` | 필수. 쉼표로 구분한 broker 주소 |
| `KAFKA_GROUP_ID` | 명령 consumer group |
| `KAFKA_SECURITY_PROTOCOL` | `PLAINTEXT`, `SSL`, `SASL_SSL` 지원 |
| `KAFKA_SASL_MECHANISM` | SASL 사용 시 `PLAIN`, `SCRAM-SHA-256`, `SCRAM-SHA-512` |
| `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD` | `SASL_SSL`에서 필수 |
| `KAFKA_SSL_CA_LOCATION` | PEM CA 파일 경로. TLS 서버 인증서 검증 유지 |
| `GITHUB_APP_ID` | 필수. GitHub App ID |
| `GITHUB_PRIVATE_KEY` | 필수. Base64로 인코딩한 PEM 개인 키 |
| `GITHUB_WEBHOOK_SECRET` | 필수. GitHub webhook 서명 검증 키 |
| `INTERNAL_API_KEY` | 필수. 내부 HTTP API 공유 키 |
| `REDIS_HOST`, `REDIS_PORT` | 토큰·설치 캐시 연결 |
