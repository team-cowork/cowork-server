# 파일·이미지 첨부

- **서비스**: cowork-chat
- **우선순위**: 🟡

## 필요 API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/channels/{channelId}/files/presigned-url` | S3 업로드용 presigned URL 발급 |
| `GET` | `/channels/{channelId}/files` | 파일 목록 조회 (FileShare 채널용) |
| `DELETE` | `/channels/{channelId}/files/{fileId}` | 파일 삭제 |

## 메시지 모델 확장

메시지 `attachments` 배열 필드 추가 필요:

```json
{
  "attachments": [
    { "type": "image", "url": "...", "name": "photo.png", "size": 102400 }
  ]
}
```
