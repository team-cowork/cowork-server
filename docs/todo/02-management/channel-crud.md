# 채널 수정·삭제 API

- **서비스**: cowork-channel
- **우선순위**: 🟠

## 필요 API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `PATCH` | `/channels/{channelId}` | 채널 이름·설명·공개여부 수정 (Admin+) |
| `DELETE` | `/channels/{channelId}` | 채널 삭제 (Admin+) |

## 요청 예시 (PATCH)

```json
{ "name": "새이름", "description": "설명", "isPrivate": false }
```
