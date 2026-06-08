# 다이렉트 메시지(DM)

- **서비스**: 신규 (`cowork-dm`)
- **우선순위**: 🟢 추후 고려

---

## 결정 사항 요약

| 항목 | 결정 |
|------|------|
| DM 범위 | 플랫폼 전체 (팀 무관) |
| 대화 규모 | 1:1 먼저, 그룹 DM 확장 가능한 구조 |
| 서비스 위치 | 신규 `cowork-dm` 서비스 |
| 기술 스택 | NestJS + MongoDB + Socket.IO |
| 대화방 생성 | Discord 방식 (프로필에서 열기, upsert) |
| DM 목록 | 참여자별 `isHidden` 플래그 (Discord 방식) |
| 차단 | Discord 전체 차단 (DM 거부 + 채널 메시지 숨김), Redis 캐싱 |
| 파일 첨부 | MinIO 연동 |
| 읽음 상태 | 지원 |
| 알림 | Kafka → cowork-notification |
| Emoji 반응 | 지원 |
| 메시지 수정·삭제 | 지원 |

---

## 아키텍처

```
Client
  │
  ├─ REST ─────────────► cowork-gateway ──► cowork-dm (NestJS)
  └─ WebSocket ────────► cowork-gateway ──► cowork-dm /dm-ws

cowork-dm
  ├── MongoDB (대화방·메시지)
  ├── Redis (차단 목록 캐시)
  ├── MinIO (파일 첨부)
  └── Kafka
        ├── Produce → dm.message.created → cowork-notification
        └── Produce → dm.block.updated   → cowork-chat (채널 메시지 숨김용)
```

### 디렉터리 구조 (3계층)

```
cowork-dm/src/
│
├── conversation/
│   ├── conversation.controller.ts
│   ├── conversation.service.ts
│   ├── conversation.repository.ts
│   ├── conversation.module.ts
│   └── schema/
│       └── dm-conversation.schema.ts
│
├── message/
│   ├── message.controller.ts
│   ├── message.service.ts
│   ├── message.repository.ts
│   ├── message.module.ts
│   └── schema/
│       └── dm-message.schema.ts
│
├── block/
│   ├── block.controller.ts
│   ├── block.service.ts
│   ├── block.module.ts
│   └── block.redis.ts              # Redis 직접 접근
│
├── gateway/
│   └── dm.gateway.ts               # WebSocket (Socket.IO)
│
├── kafka/
│   ├── notification.producer.ts
│   ├── block.producer.ts
│   └── notification-outbox.poller.ts
│
├── storage/
│   └── storage.service.ts          # MinIO presigned URL
│
├── common/
│   ├── decorator/
│   ├── guard/
│   └── dto/
│
└── eureka/
```

---

## MongoDB 스키마

### `dm_conversations` 컬렉션

```typescript
@Schema({ timestamps: true, versionKey: false })
class DmConversation {
  // 참여자 목록. 현재 1:1 = 항상 2명, 그룹 DM 확장 시 n명
  @Prop({ type: [ParticipantSchema], required: true })
  participants: Participant[];

  // 마지막 메시지 ObjectId (목록 정렬용)
  @Prop({ type: Types.ObjectId, default: null })
  lastMessageId: Types.ObjectId | null;

  @Prop({ type: Date, default: null })
  lastMessageAt: Date | null;
}

// 참여자 서브도큐먼트
@Schema({ _id: false })
class Participant {
  @Prop({ required: true }) userId: number;

  // false = 목록에 보임, true = 숨김 (Discord의 "닫기")
  // 상대방 메시지 수신 시 자동으로 false 복구
  @Prop({ default: false }) isHidden: boolean;

  @Prop({ type: Types.ObjectId, default: null })
  lastReadMessageId: Types.ObjectId | null;

  @Prop({ default: 0 }) unreadCount: number;
}
```

인덱스:
- `{ "participants.userId": 1 }` — 내 DM 목록 조회
- `{ "participants.userId": 1, "participants.isHidden": 1, lastMessageAt: -1 }` — 목록 정렬
- `{ "participants.userId": 1 }` unique pair 보장은 앱 레벨에서 upsert 처리

### `dm_messages` 컬렉션

cowork-chat `Message` 스키마에서 `teamId`, `channelId`, `projectId` 제거, `conversationId` 추가.

```typescript
@Schema({ timestamps: true, versionKey: false })
class DmMessage {
  @Prop({ type: Types.ObjectId, required: true }) conversationId: Types.ObjectId;
  @Prop({ required: true }) authorId: number;
  @Prop({ required: true, maxlength: 25000 }) content: string;
  @Prop({ enum: ['TEXT', 'FILE', 'SYSTEM'], default: 'TEXT' }) type: string;
  @Prop({ type: [Attachment], default: [] }) attachments: Attachment[];
  @Prop({ type: Types.ObjectId, default: null }) parentMessageId: Types.ObjectId | null;
  @Prop({ default: false }) isEdited: boolean;
  @Prop({ type: [EditHistory], default: [] }) editHistory: EditHistory[];
  @Prop({ type: [Reaction], default: [] }) reactions: Reaction[];
  @Prop({ type: String }) clientMessageId?: string;
  @Prop({ type: [Number], default: [] }) mentions: number[];
  @Prop({ enum: ['PENDING', 'PROCESSING', 'SENT', 'FAILED'], default: 'PENDING' })
  notificationStatus: string;
  @Prop({ default: 0 }) notificationRetryCount: number;
}
```

인덱스:
- `{ conversationId: 1, _id: -1 }` — 대화방별 메시지 최신순
- `{ clientMessageId: 1 }` unique sparse — 멱등성
- `{ notificationStatus: 1, createdAt: 1 }` — 아웃박스 워커

---

## Redis 스키마 (차단 목록)

```
Key:   dm:block:{blockerId}
Type:  Set
Value: Set<string>  // 차단된 userId 목록

# 예시
SADD dm:block:1001  1002
SISMEMBER dm:block:1001  1002  → 1 (차단됨)
```

- cowork-dm이 차단 시 Redis에 저장 + Kafka `dm.block.updated` 이벤트 발행
- cowork-chat은 `dm.block.updated` consume 후 자체 Redis에도 동기화 → 채널 메시지 조회 시 필터링

---

## REST API

### 대화방

```
POST   /dm/conversations                   대화방 열기 (upsert, targetUserId body)
GET    /dm/conversations                   내 DM 목록 (isHidden=false, lastMessageAt 내림차순)
DELETE /dm/conversations/:conversationId   대화방 숨기기 (isHidden=true)
```

### 메시지

```
GET    /dm/conversations/:conversationId/messages              메시지 목록 (커서 페이지네이션)
POST   /dm/conversations/:conversationId/messages              메시지 전송
PATCH  /dm/conversations/:conversationId/messages/:messageId   메시지 수정
DELETE /dm/conversations/:conversationId/messages/:messageId   메시지 삭제
POST   /dm/conversations/:conversationId/messages/:messageId/reactions  리액션 추가/제거
```

### 파일 업로드

```
POST   /dm/upload   MinIO presigned URL 발급 (cowork-chat storage 패턴 동일)
```

### 차단

```
POST   /dm/block/:targetUserId   차단
DELETE /dm/block/:targetUserId   차단 해제
GET    /dm/block                 차단 목록 조회
```

---

## WebSocket 이벤트

네임스페이스: `/dm`, 경로: `/dm-ws`

### Client → Server

| 이벤트 | 페이로드 | 설명 |
|--------|---------|------|
| `dm:join` | `{ conversationId }` | 대화방 룸 입장 |
| `dm:leave` | `{ conversationId }` | 대화방 룸 퇴장 |
| `dm:message` | `{ conversationId, content, type, attachments?, clientMessageId? }` | 메시지 전송 |
| `dm:message:edit` | `{ messageId, content }` | 메시지 수정 |
| `dm:message:delete` | `{ messageId }` | 메시지 삭제 |
| `dm:reaction` | `{ messageId, emoji }` | 리액션 토글 |
| `dm:read` | `{ conversationId, messageId }` | 읽음 처리 |
| `dm:typing:start` | `{ conversationId }` | 타이핑 시작 |
| `dm:typing:stop` | `{ conversationId }` | 타이핑 종료 |

### Server → Client

| 이벤트 | 페이로드 | 설명 |
|--------|---------|------|
| `dm:message:new` | MessageResponse | 새 메시지 수신 |
| `dm:message:updated` | `{ messageId, content, isEdited }` | 메시지 수정됨 |
| `dm:message:deleted` | `{ messageId }` | 메시지 삭제됨 |
| `dm:reaction:updated` | `{ messageId, reactions }` | 리액션 변경됨 |
| `dm:read:updated` | `{ conversationId, userId, messageId }` | 상대방 읽음 |
| `dm:conversation:opened` | ConversationResponse | DM 열림 (상대방 목록 복구용) |
| `dm:typing` | `{ conversationId, userId, isTyping }` | 타이핑 상태 |

---

## Kafka 이벤트

### Produce

| 토픽 | 트리거 | 소비 서비스 |
|------|--------|------------|
| `dm.message.created` | DM 메시지 전송 | cowork-notification |
| `dm.block.updated` | 차단/해제 | cowork-chat |

### `dm.message.created` 페이로드

```json
{
  "messageId": "...",
  "conversationId": "...",
  "senderId": 1001,
  "receiverId": 1002,
  "content": "안녕하세요",
  "type": "TEXT",
  "createdAt": "2026-06-08T00:00:00Z"
}
```

### `dm.block.updated` 페이로드

```json
{
  "blockerId": 1001,
  "targetId": 1002,
  "action": "BLOCK" // or "UNBLOCK"
}
```

---

## Gateway 라우팅 추가

```yaml
- id: cowork-dm
  uri: lb://cowork-dm
  predicates:
    - Path=/dm/**
- id: cowork-dm-ws
  uri: lb:ws://cowork-dm
  predicates:
    - Path=/dm-ws/**
```

---

## 구현 순서

1. **cowork-dm 서비스 생성** — NestJS 보일러플레이트, Eureka 등록, MongoDB 연결
2. **DmConversation CRUD** — 대화방 열기(upsert), 목록 조회, 숨기기
3. **DmMessage REST** — 전송, 조회(커서 페이지네이션), 수정, 삭제
4. **WebSocket Gateway** — 실시간 메시지 송수신, 읽음 처리, 타이핑
5. **파일 첨부** — MinIO presigned URL
6. **차단 기능** — Redis 저장, Kafka 이벤트 발행
7. **cowork-chat 수정** — `dm.block.updated` consume, 메시지 조회 시 차단 필터
8. **알림 연동** — `dm.message.created` → cowork-notification
9. **읽음/안읽음 카운트** — unreadCount 증감, lastReadMessageId 갱신
10. **Gateway 라우팅** — REST + WebSocket 등록
