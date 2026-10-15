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
| HTTP | `3000`        |

## 환경변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `PORT` | `3000` | HTTP 포트 |
| `KAFKA_BROKERS` | 없음 | 필수. 쉼표로 구분한 브로커 목록 |
| `KAFKA_GROUP_ID` | `cowork-github-group` | Kafka consumer group |
| `GITHUB_APP_ID` | 없음 | 필수. GitHub App ID |
| `GITHUB_PRIVATE_KEY` | 없음 | 필수. base64로 인코딩한 GitHub App PEM 키 |
| `REDIS_HOST` | `localhost` | Redis 호스트 |
| `REDIS_PORT` | `6379` | Redis 포트 |
| `INTERNAL_API_KEY` | 없음 | 필수. 내부 HTTP API의 `X-Internal-Api-Key` 값 |
| `GITHUB_WEBHOOK_SECRET` | 없음 | 필수. Webhook `X-Hub-Signature-256` 검증 비밀값 |

캐시 TTL과 재시도 횟수는 [`env.validation.ts`](src/config/env.validation.ts)의 기본값을 따릅니다.

## 개발

```bash
npm ci
npm run lint
npm test
npm run build
```
