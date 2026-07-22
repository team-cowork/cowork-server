# cowork-voice

## 역할

LiveKit 기반 음성 채널과 1:N 라이브 룸을 관리합니다.

- 음성 채널 참가·퇴장, 참가자와 세션 조회
- 라이브 룸 시작·참가·퇴장·상태 조회
- LiveKit access token 발급과 참가 권한 결정
- LiveKit webhook으로 실제 참가·퇴장·룸 종료 상태 동기화
- MongoDB outbox를 이용해 음성·라이브 이벤트를 Kafka에 전달

## 스택

- Go 1.26 + Chi
- LiveKit Server SDK
- MongoDB + Redis
- Kafka, Eureka Client, Spring Config 호환 클라이언트

## 포트와 엔드포인트

- 포트: `8089`
- 음성 API: `/voice/channels/{channel_id}/**`, `/voice/sessions/{session_id}`
- 라이브 API: `/live/channels/{channel_id}/**`
- LiveKit webhook: `POST /voice/webhook`
- Health / Prometheus / Swagger UI: `/health`, `/metrics`, `/swagger/index.html`

일반 API의 사용자는 Gateway가 전달한 `X-User-Id` 헤더로 식별합니다. webhook은 LiveKit 서명을 별도로 검증합니다.

## 의존성

- LiveKit: 룸과 access token
- MongoDB: 세션·참가자·outbox
- Redis: 활성 음성 세션 캐시
- Kafka produce: `voice.event`
- HTTP: `cowork-channel`(채널 멤버십 확인)
- Eureka, Config Server

## 환경 변수

| 공급원 | 설정 |
|---|---|
| Compose | `APP_CONFIG_URL`, `APP_PROFILE`; 운영 시 LiveKit API/WS endpoint override |
| Config Server | 포트, MongoDB DB명, Redis, LiveKit endpoint, Kafka, Channel URL, Eureka |
| Vault | `MONGODB_URI`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` |

직접 환경변수 override를 지원하지만 Compose에서는 중앙 설정을 사용합니다. Config Server 조회에 실패하거나 필수값이 없으면 기동하지 않습니다.
