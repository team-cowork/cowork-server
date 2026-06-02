# #14 읽음 상태 및 미읽 카운트 — 구현 스펙

- **서비스**: cowork-chat
- **우선순위**: 🟡

---

## 결정 사항 요약

| 항목 | 결정 |
|------|------|
| 읽음 기준 | `lastReadMessageId` (ObjectId) |
| 미읽 카운트 계산 | 매번 실시간 `countDocuments` |
| WS 이벤트 대상 | 해당 유저만 (`user:{userId}` 개인 룸) |
| 자기 메시지 | 전송 시 자동 읽음 처리 |
| read API body | `{ lastReadMessageId: string }` |
| read API 응답 | 204 No Content |
| 개인 룸 구성 | `handleConnection` 시 자동 join |
| WS 이벤트 트리거 | read API 호출 시만 emit (새 메시지 도착 시 push 없음) |
| unread 조회 범위 | 내가 가입한 채널만 (공개 + 비공개) |
| 스레드 답글 카운트 | 제외 (`parentMessageId: null` 필터) |

---

## API 명세

### 1. POST /channels/{channelId}/read

채널의 마지막 읽음 메시지를 업데이트하고, 업데이트 후의 미읽 카운트를 WS로 push한다.

**헤더**
```
X-User-Id: {userId}
X-User-Role: ADMIN | MEMBER
```

**Body**
```json
{ "lastReadMessageId": "683a1f2e4b5c6d7e8f9a0b1c" }
```

**응답**: `204 No Content`

**처리 순서**
1. `ChannelMember`에서 `{ channelId, userId }` 조회 → 멤버가 아니면 403
2. `ChannelMember.lastReadMessageId` 업데이트 (`$set`)
3. `countDocuments({ channelId, _id: { $gt: lastReadMessageId }, parentMessageId: null })` 로 unreadCount 계산
4. `user:{userId}` 룸에 `channel:unread:updated` emit

---

### 2. GET /teams/{teamId}/unread

내가 가입한 채널(공개 + 비공개 모두)의 채널별 미읽 카운트를 반환한다.

**헤더**
```
X-User-Id: {userId}
X-User-Role: ADMIN | MEMBER
```

**응답**
```json
[
  { "channelId": 1, "unreadCount": 3 },
  { "channelId": 2, "unreadCount": 0 }
]
```

**처리 순서**
1. `ChannelMember`에서 `{ teamId, userId }` 조회 → 가입 채널 목록 + `lastReadMessageId` 수집
2. 각 채널에 대해 `countDocuments` 병렬 실행:
   - `lastReadMessageId`가 있는 경우: `{ channelId, _id: { $gt: lastReadMessageId }, parentMessageId: null }`
   - `lastReadMessageId`가 null인 경우: `{ channelId, parentMessageId: null }` (전체 카운트)
3. `[{ channelId, unreadCount }]` 배열 반환

---

## WebSocket 이벤트

### channel:unread:updated

**방향**: Server → Client  
**룸**: `user:{userId}` (개인 룸)  
**트리거**: `POST /channels/{channelId}/read` 호출 후

**페이로드**
```json
{
  "channelId": 1,
  "unreadCount": 0
}
```

---

## 스키마 변경

### ChannelMember (`channel-member.schema.ts`)

```typescript
@Prop({ type: Types.ObjectId, default: null })
lastReadMessageId!: Types.ObjectId | null;
```

인덱스는 기존 `(channelId, userId)` 복합 인덱스로 충분.

---

## 자동 읽음 처리 (메시지 전송 시)

`ChatMessageConsumer`가 메시지를 MongoDB에 저장한 직후, 작성자의 `lastReadMessageId`를 저장된 메시지 `_id`로 업데이트한다.

```
// 메시지 저장 후
await channelMemberRepository.updateLastRead(channelId, authorId, savedMessage._id);
```

---

## 구현 대상 파일

### 신규 생성

| 파일 | 설명 |
|------|------|
| `src/chat/dto/read-channel.dto.ts` | `{ lastReadMessageId: string }` DTO |
| `src/chat/dto/unread-count-response.dto.ts` | `[{ channelId, unreadCount }]` 응답 DTO |

### 수정

| 파일 | 변경 내용 |
|------|---------|
| `src/chat/schema/channel-member.schema.ts` | `lastReadMessageId` 필드 추가 |
| `src/chat/chat.controller.ts` | `POST /channels/:channelId/read`, `GET /teams/:teamId/unread` 엔드포인트 추가 |
| `src/chat/chat.service.ts` | `readChannel()`, `getTeamUnread()` 메서드 추가 |
| `src/chat/chat.gateway.ts` | `handleConnection`에 `client.join(\`user:${userId}\`)` 추가 |
| `src/chat/repository/channel-member.repository.ts` | `updateLastRead()`, `findMembersByTeam()` 메서드 추가 |
| `src/chat/repository/message.repository.ts` | `countUnread()` 메서드 추가 |
| `src/chat/kafka/chat-message.consumer.ts` | 메시지 저장 후 작성자 자동 읽음 처리 호출 |

---

## countUnread 쿼리 설계

```typescript
// message.repository.ts
countUnread(channelId: number, afterId: Types.ObjectId | null): Promise<number> {
  const filter: Record<string, unknown> = { channelId, parentMessageId: null };
  if (afterId) {
    filter['_id'] = { $gt: afterId };
  }
  return this.messageModel.countDocuments(filter);
}
```

---

## 고려 사항

- **멀티 디바이스**: 같은 유저가 여러 기기에서 접속해도 `user:{userId}` 룸에 모두 포함되므로 `channel:unread:updated` 이벤트가 모든 기기에 broadcast됨.
- **새 메시지 도착 시 실시간 push 없음**: 클라이언트가 채널에 진입(`POST /read`)할 때만 이벤트 발생. 사이드바 미읽 카운트 갱신은 클라이언트가 주기적으로 `GET /teams/{teamId}/unread`를 호출하거나, 추후 Kafka consumer 연동으로 확장 가능.
- **`lastReadMessageId`가 null인 채널**: 한 번도 읽지 않은 채널은 전체 메시지를 카운트.
