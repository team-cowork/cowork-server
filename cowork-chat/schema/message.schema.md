# MongoDB Message Schema (cowork-chat)

## Collection: messages

```json
{
  "_id": "ObjectId",
  "channelId": "Long (tb_channels.id)",
  "threadId": "Long | null (tb_threads.id, null이면 채널 루트 메시지)",
  "authorId": "Long (tb_user_profiles.id)",
  "type": "STRING (TEXT | FILE | WEBHOOK | SYSTEM)",
  "content": "String | null (type=FILE이면 null 가능)",
  "attachments": [
    {
      "name": "String",
      "url": "String",
      "size": "Long (bytes)",
      "mimeType": "String"
    }
  ],
  "webhookName": "String | null (type=WEBHOOK일 때 발신자 이름)",
  "webhookAvatarUrl": "String | null",
  "isEdited": "Boolean",
  "isPinned": "Boolean",
  "createdAt": "Date",
  "updatedAt": "Date"
}
```

## Index 전략

| 필드 | 인덱스 | 목적 |
|---|---|---|
| `channelId` + `createdAt` | Compound | 채널별 메시지 최신순 조회 |
| `threadId` + `createdAt` | Compound | 스레드 메시지 조회 |
| `authorId` | Single | 사용자별 메시지 조회 |
| `isPinned` + `channelId` | Compound | 채널 고정 메시지 조회 |

## 채널 타입별 메시지 규칙

| 채널 타입 | 허용 메시지 type | 비고 |
|---|---|---|
| TEXT | TEXT, FILE, SYSTEM | 일반 채팅 |
| WEBHOOK | WEBHOOK, SYSTEM | 외부 서비스 메시지만 수신, 사용자 전송 불가 |
| FILE_SHARE | FILE, SYSTEM | 파일 첨부 필수 |
| MEETING_NOTE | TEXT, FILE, SYSTEM | 회의록 전용 포맷 |
| ACCOUNT_SHARE | SYSTEM | 별도 처리 |
| VOICE | SYSTEM | 입장/퇴장 시스템 메시지만 |

## 스레드 연결 방식

- 채널 루트 메시지: `threadId = null`
- 스레드 메시지: `threadId = tb_threads.id`
- 스레드 시작점 메시지의 `_id` (ObjectId) → MySQL `tb_threads.parent_message_id`에 저장
