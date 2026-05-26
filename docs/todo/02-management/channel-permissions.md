# 채널별 멤버 권한 설정

- **서비스**: cowork-channel
- **우선순위**: 🟠

## 필요 API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/channels/{channelId}/members/{userId}` | 비공개 채널에 멤버 초대 |
| `DELETE` | `/channels/{channelId}/members/{userId}` | 비공개 채널 멤버 제거 |
