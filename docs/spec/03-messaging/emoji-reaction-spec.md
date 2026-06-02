# Emoji 반응 구현 스펙

- **서비스**: cowork-chat
- **작성일**: 2026-05-27

---

## 1. 데이터 모델

### Message 스키마 수정

`reactions` 필드를 Message 도큐먼트에 embed.

```typescript
// message.schema.ts 추가
reactions: [
  {
    emoji: string,      // 유니코드 이모지
    userIds: number[]   // 반응한 userId 목록
  }
]
```

- userIds가 빈 배열이 되면 해당 emoji 항목을 reactions 배열에서 제거
- 인덱스 추가 불필요 (reactions는 조회 조건이 아닌 embedded 데이터)

---

## 2. REST API

### 2-1. 반응 추가

```
POST /channels/:channelId/messages/:messageId/reactions
```

**요청 Body**

```json
{ "emoji": "👍" }
```

**DTO 검증**

```typescript
@IsString()
@Matches(/^(\p{Emoji_Presentation}|\p{Extended_Pictographic})+$/u, {
  message: 'Invalid emoji format'
})
emoji: string;
```

**응답**: `200 OK` (body 없음)

**권한**: 채널 멤버만 가능 (`channelMemberRepository.exists()` 검증)

---

### 2-2. 반응 제거

```
DELETE /channels/:channelId/messages/:messageId/reactions/:emoji
```

**응답**: `200 OK` (body 없음)

**권한**: 채널 멤버 + 본인 반응만 제거 가능 (service에서 userId 기반 검증)

---

## 3. 처리 흐름 (동기)

```
POST /reactions
  → channelMemberRepository.exists() 검증
  → messageRepository.addReaction()       // MongoDB atomic 업데이트
  → chatGateway.broadcast()               // Socket.io broadcast
  → 200 OK

DELETE /reactions/:emoji
  → channelMemberRepository.exists() 검증
  → messageRepository.removeReaction()    // MongoDB atomic 업데이트
  → chatGateway.broadcast()               // Socket.io broadcast
  → 200 OK
```

---

## 4. MongoDB 업데이트 전략

### 반응 추가 (`$addToSet` + `$push`)

```typescript
// 1단계: 이모지 항목이 이미 있으면 userId만 추가
const result = await Message.updateOne(
  { _id: messageId, 'reactions.emoji': emoji },
  { $addToSet: { 'reactions.$.userIds': userId } }
);

// 2단계: 이모지 항목이 없으면 새 항목 push
if (result.matchedCount === 0) {
  await Message.updateOne(
    { _id: messageId, 'reactions.emoji': { $ne: emoji } },
    { $push: { reactions: { emoji, userIds: [userId] } } }
  );
}
```

### 반응 제거 (`$pull` + 빈 항목 정리)

```typescript
// 1단계: 본인 userId 제거
await Message.updateOne(
  { _id: messageId, 'reactions.emoji': emoji },
  { $pull: { 'reactions.$.userIds': userId } }
);

// 2단계: userIds가 빈 배열이 된 항목 제거
await Message.updateOne(
  { _id: messageId },
  { $pull: { reactions: { userIds: { $size: 0 } } } }
);
```

---

## 5. WebSocket 이벤트

| 이벤트 | 방향 | 설명 |
|--------|------|------|
| `message:reaction:added` | S→C | 반응 추가됨 |
| `message:reaction:removed` | S→C | 반응 제거됨 |

### Payload

```typescript
// message:reaction:added / message:reaction:removed 공통
{
  messageId: string;   // ObjectId string
  channelId: number;
  emoji: string;
  userId: number;
  count: number;       // 해당 emoji의 현재 총 반응 수
}
```

- broadcast 대상: `chat:{channelId}` 룸
- `count`는 MongoDB 업데이트 후 `userIds.length`로 계산

---

## 6. 메시지 목록 응답 수정

`GET /channels/:channelId/messages` 응답의 `MessageResponseDto`에 `reactions` 필드 추가.

```typescript
// MessageResponseDto 수정
reactions: Array<{
  emoji: string;
  count: number;
  myReaction: boolean;  // 요청자(JWT userId)가 반응했는지 여부
}>;
```

변환 로직:
```typescript
reactions: message.reactions.map(r => ({
  emoji: r.emoji,
  count: r.userIds.length,
  myReaction: r.userIds.includes(ctx.userId),
}))
```

---

## 7. 구현 체크리스트

- [ ] `message.schema.ts` — `reactions` 필드 추가
- [ ] `message.repository.ts` — `addReaction()`, `removeReaction()` 메서드 추가
- [ ] `add-reaction.dto.ts` — 요청 DTO (emoji 유효성 검증 포함)
- [ ] `chat.controller.ts` — POST/DELETE 엔드포인트 추가
- [ ] `chat.service.ts` — `addReaction()`, `removeReaction()` 추가
- [ ] `message-response.dto.ts` — `reactions` 필드 추가
