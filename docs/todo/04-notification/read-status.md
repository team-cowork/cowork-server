# 읽음 상태 및 미읽 카운트

- **서비스**: cowork-chat 또는 신규
- **우선순위**: 🟡

## 필요 API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/channels/{channelId}/read` | 채널 마지막 읽음 시각 업데이트 |
| `GET` | `/teams/{teamId}/unread` | 팀 내 채널별 미읽 수 조회 |

## WebSocket 이벤트

| 이벤트 | 방향 | 설명 |
|--------|------|------|
| `channel:unread:updated` | S→C | 미읽 카운트 변경 알림 |
