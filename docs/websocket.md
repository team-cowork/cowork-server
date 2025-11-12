# 실시간 통신 (STOMP)

## 연결

- 엔드포인트: `/ws`
- CONNECT 프레임에 `Authorization: Bearer {accessToken}` 헤더를 넣습니다.
- 토큰이 없거나 유효하지 않으면 연결을 거부합니다.

```javascript
const client = new Client({
  brokerURL: "wss://api.cowork.team/ws",
  connectHeaders: { Authorization: `Bearer ${accessToken}` },
});
```

## 구독 경로

| 경로 | 내용 | 권한 |
|---|---|---|
| `/topic/channels/{channelId}` | 메시지 생성·수정·삭제 | 채널을 읽을 수 있는 사용자 |
| `/topic/channels/{channelId}/voice` | 음성 참여자 변경 | 채널을 읽을 수 있는 사용자 |
| `/topic/users/{userId}/status` | 사용자 상태 변경 | 로그인한 사용자 |
| `/user/queue/errors` | 본인에게 보내는 오류 | 로그인한 사용자 |

## 이벤트 형식

```json
{ "type": "MESSAGE_CREATED", "payload": { "id": 5, "channelId": 10, "content": "안녕하세요" } }
```

`type`은 `MESSAGE_CREATED`, `MESSAGE_UPDATED`, `MESSAGE_DELETED` 중 하나입니다. 삭제 이벤트의 `payload`에는 `messageId`만 있습니다.

## 메시지 전송

- 경로: `/app/channels/{channelId}/messages`
- 본문은 `POST /channels/{channelId}/messages`와 같습니다.
- 처리 중 오류가 나면 `/user/queue/errors`로 오류 응답을 보냅니다.

메시지는 트랜잭션이 커밋된 뒤에 구독자에게 전송합니다. 저장에 실패한 메시지는 전송하지 않습니다.
