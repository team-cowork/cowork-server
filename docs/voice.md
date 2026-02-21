# 음성 채널

음성 통화는 LiveKit으로 처리하고, 서버는 세션과 참여자 기록만 관리합니다.

1. 클라이언트가 `POST /voice/channels/{channelId}/join`을 호출합니다.
2. 서버는 진행 중인 세션을 찾거나 새로 만들고, LiveKit 접속 토큰을 발급합니다.
3. 클라이언트는 응답의 `serverUrl`과 `token`으로 LiveKit 방에 접속합니다.
4. 나갈 때는 `POST /voice/channels/{channelId}/leave`를 호출합니다.

채널의 최대 인원 설정(`max_participants`)을 넘으면 참여할 수 없습니다.

## LiveKit 웹훅

클라이언트가 나가기 API를 호출하지 못하고 연결이 끊기는 경우가 있어, LiveKit 웹훅으로 상태를 맞춥니다.

| 이벤트 | 처리 |
|---|---|
| `participant_joined` | 참여 기록이 없으면 추가 |
| `participant_left` | 참여자 퇴장 처리, 마지막 참여자면 세션 종료 |
| `room_finished` | 세션 종료 |

웹훅 요청은 `Authorization` 헤더의 JWT와 본문 SHA-256 해시로 검증합니다.

## 로컬 개발

`docker compose up -d`로 LiveKit 개발 서버(7880)가 함께 실행됩니다.
개발 서버의 API 키와 시크릿은 `devkey` / `secret`이며 `.env.example`에 들어 있습니다.
로컬에서 웹훅을 받으려면 `livekit.yaml`의 `webhook.urls`에 `http://host.docker.internal:8080/voice/webhook`을 넣습니다.
