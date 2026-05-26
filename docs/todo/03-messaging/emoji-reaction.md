# 메시지 Emoji 반응

- **서비스**: cowork-chat
- **우선순위**: 🟡

## 필요 API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/channels/{channelId}/messages/{messageId}/reactions` | 반응 추가 |
| `DELETE` | `/channels/{channelId}/messages/{messageId}/reactions/{emoji}` | 반응 제거 |

**요청 예시 (POST)**
```json
{ "emoji": "👍" }
```

## WebSocket 이벤트

| 이벤트 | 방향 | 설명 |
|--------|------|------|
| `message:reaction:added` | S→C | 반응 추가됨 |
| `message:reaction:removed` | S→C | 반응 제거됨 |
