# MongoDB Schema (cowork-chat)

`cowork-chat`이 MongoDB에 저장하는 `messages`와 `channelmembers` collection의 도큐먼트 구조 및 인덱스 설계 문서입니다. 검색 색인 아웃박스가 함께 사용하는 `message_search_tombstones`와 `message_search_index_state`도 포함합니다. 다른 projection collection은 이 문서의 범위에 포함하지 않습니다.

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
  "notificationClaimId": "String | null (점유 호출마다 발급하는 식별자)",
  "searchIndexStatus": "STRING (PENDING | PROCESSING | SYNCED | FAILED | DELETING | SKIPPED)",
  "searchIndexVersion": "Number (메시지별 단조 증가 색인 버전, 색인 대상이 아니면 0)",
  "searchIndexSyncedVersion": "Number (색인에 마지막으로 반영된 버전)",
  "searchIndexRetryCount": "Number",
  "searchIndexNextAttemptAt": "Date | null",
  "searchIndexProcessingStartedAt": "Date | null",
  "searchIndexClaimId": "String | null (점유 호출마다 발급하는 식별자)",
  "searchIndexLastError": "String | null",
  "searchIndexSyncedAt": "Date | null",
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
| `searchIndexStatus` + `searchIndexNextAttemptAt` | 색인 아웃박스 워커의 대기 항목 처리 순서. 필드가 없는 레거시 문서도 `null`로 인덱싱되어 백필 대상이 됨 |
| `updatedAt` + `_id` | 전체 재구축의 catch-up 스캔이 변경된 메시지만 순회 |

## 알림 아웃박스 패턴

`notificationStatus`로 `notification.trigger` Kafka 발행 처리를 추적합니다. projection readiness가 열린 동안 [폴러](../src/chat/kafka/notification-outbox.poller.ts)가 5초마다 최대 10개를 `PENDING`에서 `PROCESSING`으로 전환합니다. 성공하면 `SENT`, 실패하면 실패 횟수를 늘리고 `PENDING`으로 되돌리며, 누적 3회 실패하면 `FAILED`로 남깁니다. `SENT`는 폴러 처리 완료 상태이며 최종 FCM 전달 성공을 뜻하지 않습니다.

`notificationProcessingStartedAt`이 2분 이상 지난 문서는 최소 1분 간격의 회수 단계에서 `PENDING`으로 되돌립니다. 발행 뒤 상태 저장 전에 중단되면 같은 알림을 다시 발행할 수 있습니다.

폴러는 후보를 `PROCESSING`으로 전환한 뒤 이번 호출에서 발급한 `notificationClaimId`로 실제 점유분만 되읽습니다. 구분자로 점유 시각을 쓰면 두 워커가 같은 밀리초에 점유했을 때 서로의 점유분까지 함께 읽어 같은 메시지의 알림이 중복 발행되고 미읽 카운트가 두 번 증가합니다.

`clientMessageId`의 sparse unique 인덱스는 명시적으로 저장한 `null`을 제외하지 않습니다. 멱등성 키가 없는 메시지는 필드를 생략해야 합니다.

## 검색 색인 아웃박스 패턴

MongoDB 메시지가 원본이고 Elasticsearch는 파생 색인입니다. 메시지 도큐먼트 자체가 색인 아웃박스이므로 본문·고정 상태 변경과 색인 의도가 같은 쓰기에 남습니다. 색인 대상은 `teamId`와 `projectId`가 모두 있고 `type`이 `SYSTEM`이 아닌 메시지이며, 대상이 아닌 메시지는 `SKIPPED`로 확정합니다.

`searchIndexVersion`은 변경마다 증가하는 메시지별 버전이고, Elasticsearch 외부 버전(`version_type: external`)으로 그대로 전달됩니다. 색인은 항상 부분 갱신이 아니라 최신 전체 문서를 `UPSERT`하므로 최초 색인이 누락된 메시지도 이후 어떤 변경으로든 복원되고, 지연된 쓰기는 버전 충돌로 버려집니다.

[색인 워커](../src/chat/search/message-search-outbox.poller.ts)가 3초마다 `PENDING` 항목을 배치로 `PROCESSING`으로 전환합니다. 성공하면 `SYNCED`, 재시도 가능한 오류이면 상한이 있는 백오프로 다시 `PENDING`이 되며, 문서·매핑 계약 위반처럼 재시도가 의미 없는 경우에만 `FAILED`로 남습니다. `searchIndexProcessingStartedAt`이 2분 이상 지난 문서는 회수합니다. 되읽기 조건에는 점유 시각이 아니라 호출마다 발급하는 `searchIndexClaimId`를 사용합니다. 두 워커가 같은 밀리초에 점유하면 시각만으로는 서로의 점유분을 구분할 수 없기 때문입니다.

완료 갱신은 `searchIndexVersion`이 점유 당시와 같을 때만 적용되므로, 점유 중에 메시지가 다시 변경되면 새 `PENDING` 의도가 살아남습니다. 아웃박스 상태 전이는 `updatedAt`을 바꾸지 않아 재구축 catch-up 스캔이 실제 내용 변경만 따라갑니다.

삭제는 `삭제 버전 예약 → tombstone 기록 → 메시지 삭제` 순서로 진행합니다. 예약은 메시지를 `DELETING`으로 고정해 이후 본문·고정 변경이 버전을 올리지 못하게 하므로, tombstone 버전은 그 메시지의 어떤 upsert보다 항상 큽니다. tombstone 없이 `DELETING`에 5분 이상 머문 메시지는 삭제가 커밋되지 않은 것으로 보고 다시 색인 대상으로 되돌립니다.

## Collection: message_search_tombstones

```json
{
  "_id": "ObjectId",
  "messageId": "String (삭제된 메시지의 ObjectId 문자열, 색인 문서 ID와 동일)",
  "teamId": "Number",
  "projectId": "Number",
  "channelId": "Number",
  "version": "Number (삭제 시점에 메시지에서 예약한 색인 버전)",
  "status": "STRING (PENDING | PROCESSING | DELETED | FAILED)",
  "retryCount": "Number",
  "nextAttemptAt": "Date | null",
  "processingStartedAt": "Date | null",
  "claimId": "String | null (점유 호출마다 발급하는 식별자)",
  "lastError": "String | null",
  "deletedAt": "Date | null",
  "expiresAt": "Date (TTL 만료 시각)",
  "createdAt": "Date",
  "updatedAt": "Date"
}
```

| 인덱스                    | 목적                                                       |
|---------------------------|------------------------------------------------------------|
| `messageId` (unique)      | 같은 메시지의 tombstone 중복 생성 방지                     |
| `status` + `nextAttemptAt`| 워커의 대기 tombstone 처리 순서                            |
| `updatedAt` + `_id`       | 전체 재구축 catch-up의 삭제 재생 순회                      |
| `expiresAt` (TTL)         | 보존 기간이 지난 tombstone 자동 정리                       |

메시지는 hard delete되므로 삭제 색인 명령은 이 collection에만 남습니다. 워커는 tombstone을 처리할 때 MongoDB 삭제를 먼저 완결한 뒤 색인 문서를 제거하므로, tombstone 기록 직후 중단되어도 삭제가 끝까지 완료됩니다. 보존 기간(`CHAT_SEARCH_TOMBSTONE_RETENTION_DAYS`, 기본 7일)은 전체 재구축이 삭제를 재생할 수 있을 만큼 길어야 합니다.

## Collection: message_search_index_state

```json
{
  "_id": "String (message-search-index 고정 값)",
  "lastRebuildStartedAt": "Date | null",
  "lastRebuildSucceededAt": "Date | null",
  "lastRebuildFailedAt": "Date | null",
  "lastRebuildIndex": "String | null (마지막 재구축 대상 물리 index)",
  "lastRebuildError": "String | null",
  "lastRebuildDocumentCount": "Number | null",
  "lastRebuildScanCursor": "String | null (재개 지점의 _id)",
  "legacyBackfillCompletedAt": "Date | null",
  "createdAt": "Date",
  "updatedAt": "Date"
}
```

replica가 공유하는 단일 도큐먼트로, 마지막 전체 재구축 결과와 레거시 백필 완료 여부를 남깁니다. 운영 절차는 [서비스 README](../README.md#검색-색인-운영)를 참고합니다.

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
