# MongoDB Schema (cowork-chat)

`cowork-chat`이 MongoDB에 저장하는 `messages`와 `channelmembers` collection의 도큐먼트 구조 및 인덱스 설계 문서입니다. 다른 projection collection은 이 문서의 범위에 포함하지 않습니다.

소스: [메시지 스키마](../src/chat/schema/message.schema.ts), [채널 멤버 스키마](../src/chat/schema/channel-member.schema.ts), [모델 등록](../src/chat/chat.module.ts).
두 모델은 collection 이름을 명시하지 않아 Mongoose의 기본 복수화 규칙을 따릅니다. 공통 규칙은 [데이터베이스 규칙](../../.claude/rules/database.md)을 참고합니다.

두 스키마 모두 `timestamps: true`, `versionKey: false`를 사용하므로 `createdAt`·`updatedAt`은 자동 관리하고 `__v`는 생성하지 않습니다. 아래 예시는 필드별 저장값을 설명하며 실제 JSON payload는 아닙니다. ID와 파일 크기는 현재 `Number`로 선언되어 있고, `sourceVersion`은 `BigInt`(BSON 64비트 정수)입니다.

## Collection: messages

```json
{
  "_id": "ObjectId",
  "teamId": "Number | null (DM 채널 메시지는 null)",
  "projectId": "Number | null (프로젝트 무관 채널은 null)",
  "channelId": "Number (cowork-channel의 채널 ID)",
  "authorId": "Number (cowork-user의 사용자 ID)",
  "type": "STRING (TEXT | FILE | SYSTEM)",
  "content": "String (최대 25,000자, FILE 타입은 파일 설명 텍스트)",
  "attachments": [
    { "name": "String", "url": "String", "size": "Number (bytes)", "mimeType": "String" }
  ],
  "parentMessageId": "ObjectId | null (스레드 부모 메시지, 최상위 메시지는 null)",
  "isEdited": "Boolean",
  "editHistory": [{ "content": "String", "editedAt": "Date" }],
  "isPinned": "Boolean",
  "reactions": [{ "emoji": "String", "userIds": ["Number"] }],
  "clientMessageId": "String (선택적 멱등성 키, 미지정 시 필드 생략)",
  "mentions": ["Number"],
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
| `clientMessageId` (unique, sparse) | collection 전체에서 같은 멱등성 키의 중복 저장 방지. 필드가 없는 문서만 인덱스에서 제외 |
| `mentions` | 멘션된 사용자 기준 조회 |
| `notificationStatus` + `createdAt` | 아웃박스 워커의 PENDING 메시지 처리 순서 |

## 알림 아웃박스 패턴

`notificationStatus`로 `notification.trigger` Kafka 발행 처리를 추적합니다. projection readiness가 열린 동안 [폴러](../src/chat/kafka/notification-outbox.poller.ts)가 5초마다 최대 10개를 `PENDING`에서 `PROCESSING`으로 전환합니다. 성공하면 `SENT`, 실패하면 실패 횟수를 늘리고 `PENDING`으로 되돌리며, 누적 3회 실패하면 `FAILED`로 남깁니다. `SENT`는 폴러 처리 완료 상태이며 최종 FCM 전달 성공을 뜻하지 않습니다.

`notificationProcessingStartedAt`이 2분 이상 지난 문서는 최소 1분 간격의 회수 단계에서 `PENDING`으로 되돌립니다. 발행 뒤 상태 저장 전에 중단되면 같은 알림을 다시 발행할 수 있습니다.

`clientMessageId`의 sparse unique 인덱스는 명시적으로 저장한 `null`을 제외하지 않습니다. 멱등성 키가 없는 메시지는 필드를 생략해야 합니다.

## Collection: channelmembers

```json
{
  "_id": "ObjectId",
  "channelId": "Number",
  "teamId": "Number | null (DM 채널은 null)",
  "channelType": "STRING (기본 TEXT, cowork-channel 멤버십 이벤트로 동기화)",
  "isHidden": "Boolean (DM 대화 숨김, 상대 메시지 수신 시 자동 복구)",
  "userId": "Number",
  "role": "STRING (기본 MEMBER)",
  "lastReadMessageId": "ObjectId | null",
  "deleted": "Boolean (channel.member.event LEAVE tombstone, 기본 false)",
  "sourceOccurredAt": "Date (마지막으로 적용한 원본 이벤트 발생 시각)",
  "sourceVersion": "BigInt (원본 이벤트의 epoch nanoseconds, 정렬 기준)",
  "createdAt": "Date",
  "updatedAt": "Date"
}
```

| 인덱스                          | 목적                                                                      |
|---------------------------------|---------------------------------------------------------------------------|
| `channelId` + `userId` (unique) | 동일 사용자의 중복 가입 방지                                              |
| `userId` + `teamId`             | 사용자 단위/팀 단위 멤버십 조회 공용 (prefix로 `userId` 단독 조회도 커버) |

[MembershipConsumer](../src/membership/membership.consumer.ts)는 원본 버전으로 이벤트 순서를 비교하고, 탈퇴 시 문서를 삭제하는 대신 `deleted: true`를 보존합니다. 멤버십 조회는 tombstone을 제외하며, 과거 가입 이벤트로 탈퇴 상태가 복구되지 않도록 합니다.

현재 `parentMessageId`·`lastReadMessageId`는 서비스에서 `ObjectId` 값으로 저장하지만, `@Prop({ type: Types.ObjectId })` 선언은 현재 의존성 조합에서 `Mixed` 스키마 경로로 생성됩니다. 따라서 스키마 자체가 ObjectId 타입을 강제한다고 가정하면 안 됩니다. 이 선언의 수정은 별도 코드 작업이 필요합니다.
