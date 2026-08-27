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
`(consumer_group, topic, partition, topic_id, next_offset)` checkpoint에서 재생을 시작합니다. readiness
poll마다 broker의 topic UUID, partition 구성, earliest/end offset을 다시 읽고 모든 공유 checkpoint의
`next_offset`이 그때의 end와 정확히 같으며 retained range 안의 full-snapshot marker가 있을 때만
`/health/ready`를 200으로 유지합니다. Eureka 상태도 이
전이를 계속 따라가며 준비되지 않았을 때 `OUT_OF_SERVICE`, 준비됐을 때 `UP`입니다. 멤버십 조회 결과가
없을 때는 broker current-high를 동기 확인하고 한 번 더 조회하므로, 최신성을 확인할 수 없으면 403 대신
503을 반환합니다. 잘못된 계약의 record는 MongoDB dead-letter collection에 먼저 격리하고, 같은
checkpoint document에서 state-gap latch와 offset을 원자 갱신해 readiness를 즉시 닫습니다. 이 latch는
잘못된 offset 뒤에서 완료된 서로 다른 두 serialized full snapshot의 marker 전에는 해제하지 않습니다.
일시적인 MongoDB 오류는 성공할 때까지 같은 record를 재시도합니다.
저장된 checkpoint가 Kafka의 현재 earliest보다 작거나 end보다 크거나, 같은 이름의 topic UUID가
달라졌으면 자동 이동하지 않고 readiness를 닫습니다. 이 경우 projection과 checkpoint를 함께
재구축해야 합니다. Broker가 topic UUID metadata를 제공하지 못하는 경우에도 fail-closed하므로 Kafka
2.8 이상이 필요합니다.

`topic_id`가 없거나 broker의 현재 topic UUID와 다른 checkpoint도 자동 채택하지 않습니다. 이 경우
`channel_memberships` projection과 해당 consumer group의 `projection_checkpoints`를 함께 재구축해야
합니다.
