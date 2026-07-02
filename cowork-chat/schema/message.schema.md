# MongoDB Schema (cowork-chat)

소스: `src/chat/schema/message.schema.ts`, `src/chat/schema/channel-member.schema.ts`.
공통 컨벤션(스키마 옵션, 인덱스 정의 방식 등)은 서비스 루트 `README.md`의 "Mongoose 스키마 컨벤션" 절 참고.

## Collection: messages

```json
{
  "_id": "ObjectId",
  "teamId": "Long | null (DM 채널 메시지는 null)",
  "projectId": "Long | null (프로젝트 무관 채널은 null)",
  "channelId": "Long (channel-service의 채널 ID)",
  "authorId": "Long (user-service의 사용자 ID)",
  "type": "STRING (TEXT | FILE | SYSTEM)",
  "content": "String (최대 25,000자, FILE 타입은 파일 설명 텍스트)",
  "attachments": [
    { "name": "String", "url": "String", "size": "Long (bytes)", "mimeType": "String" }
  ],
  "parentMessageId": "ObjectId | null (스레드 부모 메시지, 최상위 메시지는 null)",
  "isEdited": "Boolean",
  "editHistory": [{ "content": "String", "editedAt": "Date" }],
  "isPinned": "Boolean",
  "reactions": [{ "emoji": "String", "userIds": ["Long"] }],
  "clientMessageId": "String | null (멱등성 키, sparse unique)",
  "mentions": ["Long"],
  "notificationStatus": "STRING (PENDING | PROCESSING | SENT | FAILED)",
  "notificationRetryCount": "Number",
  "notificationProcessingStartedAt": "Date | null",
  "createdAt": "Date",
  "updatedAt": "Date"
}
```

## Index 전략

| 인덱스 | 목적 |
|---|---|
| `channelId` + `_id` (desc) | 채널별 메시지 최신순 커서 페이지네이션 |
| `authorId` | 사용자별 메시지 조회 |
| `parentMessageId` | 스레드 답글 필터링 |
| `channelId` + `parentMessageId` + `_id` (desc) | 채널 내 스레드 답글 목록 조회 |
| `isPinned` + `channelId` | 채널 고정 메시지 조회 |
| `clientMessageId` (unique, sparse) | 클라이언트 재시도로 인한 메시지 중복 생성 방지 |
| `mentions` | 멘션된 사용자 기준 조회 |
| `notificationStatus` + `createdAt` | 아웃박스 워커의 PENDING 메시지 처리 순서 |

## 알림 아웃박스 패턴

`notificationStatus`(`PENDING`→`PROCESSING`→`SENT`/`FAILED`)로 `notification.trigger` Kafka 발행을 추적한다. `notificationProcessingStartedAt`이 일정 시간 이상 경과하면 폴러가 해당 메시지를 다시 `PENDING`으로 회수해 워커 크래시로 인한 영구 stuck을 방지한다.

## Collection: channel_members

```json
{
  "_id": "ObjectId",
  "channelId": "Long",
  "teamId": "Long | null (DM 채널은 null)",
  "channelType": "STRING (기본 TEXT, channel-service 멤버십 이벤트로 동기화)",
  "isHidden": "Boolean (DM 대화 숨김, 상대 메시지 수신 시 자동 복구)",
  "userId": "Long",
  "role": "STRING (기본 MEMBER)",
  "lastReadMessageId": "ObjectId | null",
  "createdAt": "Date",
  "updatedAt": "Date"
}
```

| 인덱스 | 목적 |
|---|---|
| `channelId` + `userId` (unique) | 동일 사용자의 중복 가입 방지 |
| `userId` + `teamId` | 사용자 단위/팀 단위 멤버십 조회 공용 (prefix로 `userId` 단독 조회도 커버) |
