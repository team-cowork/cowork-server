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
- Liveness / readiness: `/health`, `/health/ready`
- Prometheus / Swagger UI: `/metrics`, `/swagger/index.html`

일반 API의 사용자는 Gateway가 전달한 `X-User-Id` 헤더로 식별합니다. webhook은 LiveKit 서명을 별도로 검증합니다.

## 의존성

- LiveKit: 룸과 access token
- MongoDB: 세션·참가자·outbox·채널 멤버십 투영, projection checkpoint/dead letter
- Redis: 활성 음성 세션 캐시
- Kafka produce: `voice.event`
- Kafka consume: `channel.member.event`(채널 멤버십 확인)
- Eureka, Config Server

## 환경 변수

| 공급원 | 설정 |
|---|---|
| Compose | `APP_CONFIG_URL`, `APP_PROFILE`; 운영 시 LiveKit API/WS endpoint override |
| Config Server | 포트, MongoDB DB명, Redis, LiveKit endpoint, Kafka topic/group, Eureka |
| Vault | `MONGODB_URI`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` |

직접 환경변수 override를 지원하지만 Compose에서는 중앙 설정을 사용합니다. Config Server 조회에 실패하거나 필수값이 없으면 기동하지 않습니다.

`channel.member.event` 소비자는 broker consumer-group offset 대신 MongoDB의 공유
`(consumer_group, topic, partition, next_offset)` checkpoint에서 재생을 시작합니다. Kafka assignment 때
topic 전체 partition의 end offset을 고정하고 모든 공유 checkpoint가 그 지점에 도달해야
`/health/ready`가 200이 되며 Eureka에 `UP`으로 등록됩니다. 그 전에는 멤버십이 필요한 API가 503을
반환합니다. 잘못된 계약의 record는 MongoDB dead-letter collection에 격리한 뒤 checkpoint를
진전시키고, 일시적인 MongoDB 오류는 성공할 때까지 같은 record를 재시도합니다.
저장된 checkpoint가 Kafka의 현재 earliest보다 작거나 end보다 크면 보존 이력 손실 또는 topic
재생성으로 간주해 자동 이동하지 않고 readiness를 닫습니다. 이 경우 projection과 checkpoint를 함께
재구축해야 합니다.
