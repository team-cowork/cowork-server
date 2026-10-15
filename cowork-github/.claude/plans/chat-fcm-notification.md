# Chat FCM 푸시 알림 + 멘션/답장 구현 스펙

## 목표
채팅 메시지 전송 시 디스코드 스타일의 FCM 푸시 알림 발송.  
멘션/답장 대상자는 뮤트 설정 무시하고 무조건 알림.

---

## 최종 설계 결정사항

| 항목 | 결정 |
|------|------|
| 멘션 파싱 | 클라이언트 `mentions[]` 단일 소스. 서버는 채널 멤버 유효성 검증만 |
| content @이름 파싱 | **하지 않음** (이름→userId 역매핑 불가) |
| 답장 forced 알림 | parentMessageId 있으면 원본 작성자 **무조건** forcedUserIds 포함 |
| mentions MongoDB 저장 | **저장** (나중에 "나를 멘션한 메시지" 필터링 활용) |
| 팀 이름 조회 | notification → `GET /teams/{teamId}` HTTP 호출 |
| 발신자 이름 조회 | notification → `GET /users/{authorId}` HTTP 호출 |
| 팀/유저 조회 실패 시 | **알림 전체 스킵** (fallback 없음) |
| 알림 title 포맷 | `{팀 이름}` (프로젝트 API 없으므로 #프로젝트명 생략) |
| 알림 body 포맷 | `{nickname \|\| name}: {content}\n{YYYY-MM-DD HH:MM}` (KST) |
| content 길이 제한 | 알림 body에서 content는 최대 100자 (초과 시 `...` 말줄임) |
| Kafka 신뢰성 | **Transactional Outbox Pattern** (메시지 저장 + outbox 원자적 기록) |
| MongoDB 구성 변경 | Standalone → **Single-node Replica Set** (트랜잭션 지원) |
| Kafka 발행 실패 | outbox poller가 재시도 (at-least-once 허용) |

---

## 아키텍처 흐름

```
Client
  │  POST /channels/:channelId/messages
  │  { mentions: [userId...], parentMessageId?, content, ... }
  ▼
ChatController
  │  checkMembership → validate mentions (채널 멤버만)
  ▼
ChatMessageProducer → Kafka: chat.message
  │
  └─ {queued: true} 즉시 응답
  
  ▼ (async)
ChatMessageConsumer (Kafka: chat.message)
  │
  ├── MongoDB 트랜잭션 시작
  │     ├── messages 컬렉션에 메시지 저장 (mentions 포함)
  │     └── notification_outbox 컬렉션에 아웃박스 도큐먼트 저장
  │   트랜잭션 커밋
  │
  ├── Socket.io broadcast (`chat:{channelId}` room)
  │
  └── (OutboxPoller가 별도로 처리)

OutboxPoller (setInterval, chat 서비스 내부)
  ├── notification_outbox에서 PENDING 항목 조회
  ├── Kafka: notification.trigger 발행
  └── 성공 시 outbox 항목 삭제 (또는 SENT 마킹)

  ▼
cowork-notification (Go, Kafka: notification.trigger consumer)
  ├── targetUserIds 중 preference 확인 (뮤트 여부)
  ├── forcedUserIds는 preference 체크 **스킵** (무조건 포함)
  ├── GET /teams/{teamId} → title 조회 (실패 시 알림 스킵)
  ├── GET /users/{authorId} → 발신자 이름 조회 (실패 시 알림 스킵)
  └── FCM 배치 발송
```

---

## 변경 파일 목록

### 1. docker-compose.yml
- MongoDB Standalone → Single-node Replica Set 전환

### 2. cowork-chat (NestJS)

| 파일 | 변경 내용 |
|------|-----------|
| `src/chat/dto/send-message.dto.ts` | `mentions?: number[]` 필드 추가 |
| `src/chat/schema/message.schema.ts` | `mentions: number[]` 필드 추가 |
| `src/chat/kafka/chat-message.producer.ts` | `ChatMessageEvent`에 `mentions?: number[]` 추가 |
| `src/chat/kafka/chat-message.consumer.ts` | MongoDB 트랜잭션으로 저장+outbox 원자 기록, outbox poller 포함 |
| `src/chat/schema/notification-outbox.schema.ts` | **신규** - Outbox 도큐먼트 스키마 |
| `src/chat/kafka/notification-trigger.producer.ts` | **신규** - `notification.trigger` Kafka producer |
| `src/chat/chat.module.ts` | 신규 provider 등록, ChannelMember + Outbox 모델 주입 |

### 3. cowork-notification (Go)

| 파일 | 변경 내용 |
|------|-----------|
| `internal/infra/kafka/consumer.go` | `ForcedUserIDs` 필드 추가, CHAT_MESSAGE 시 rich body 생성 |
| `internal/domain/token/ports.go` | `Notify` 시그니처에 `forcedUserIDs []int64` 추가 |
| `internal/domain/token/service.go` | forcedUserIDs preference 체크 스킵 로직 |
| `internal/infra/team/client.go` | **신규** - `GET /teams/{teamId}` HTTP client |
| `internal/infra/user/client.go` | **신규** - `GET /users/{authorId}` HTTP client |
| `internal/config/config.go` | `TeamServiceURL`, `UserServiceURL` 추가 |
| `cmd/server/main.go` | team/user client 주입 |
| `internal/infra/kafka/consumer_test.go` | 시그니처 변경 반영 |
| `internal/domain/token/service_test.go` | `forcedUserIDs` 파라미터 반영 |

---

## 상세 스펙

### SendMessageDto 변경
```typescript
mentions?: number[]  // 채널 멤버 userId 목록. 서버에서 멤버 여부 검증 후 필터링
```

### Message Schema 변경
```typescript
mentions: number[]  // default: []
```
인덱스 추가: `{ mentions: 1 }` (나를 멘션한 메시지 조회용)

### ChatMessageEvent 변경
```typescript
interface ChatMessageEvent {
  // 기존 필드들...
  mentions?: number[]  // 추가
}
```

### notification_outbox 스키마
```typescript
{
  type: 'CHAT_MESSAGE'
  targetUserIds: number[]   // 채널 멤버 전원 - author
  forcedUserIds: number[]   // mentions(검증) + 답장 원본 작성자
  data: {
    channelId: number
    teamId: number
    authorId: number
    content: string
    occurredAt: string      // ISO 8601
  }
  status: 'PENDING' | 'SENT'
  createdAt: Date
}
```

### forcedUserIds 계산 로직 (ChatMessageConsumer)
```
1. channelMembers = memberModel.find({ channelId })
2. memberIdSet = new Set(channelMembers.map(m => m.userId))
3. targetUserIds = [...memberIdSet].filter(id => id !== authorId)

4. forcedSet = new Set()
   - mentions(from event).forEach(id => {
       if (id !== authorId && memberIdSet.has(id)) forcedSet.add(id)
     })
   - if (parentMessageId) {
       parent = await messageModel.findById(parentMessageId)
       if (parent && parent.authorId !== authorId && memberIdSet.has(parent.authorId)) {
         forcedSet.add(parent.authorId)
       }
     }

5. forcedUserIds = [...forcedSet]
```

### NotificationTriggerEvent (Kafka 이벤트 구조)
```json
{
  "type": "CHAT_MESSAGE",
  "targetUserIds": [101, 102, 103],
  "forcedUserIds": [102],
  "data": {
    "channelId": 42,
    "teamId": 7,
    "authorId": 100,
    "content": "안녕하세요",
    "occurredAt": "2026-04-22T14:32:00.000Z"
  }
}
```

### OutboxPoller 구현 (chat 서비스 내부)
- `setInterval(5000)` - 5초마다 PENDING 항목 조회
- 배치 사이즈: 최대 10건씩 처리
- Kafka 발행 성공 시 해당 도큐먼트 삭제
- 발행 실패 시 그대로 PENDING 유지 → 다음 폴링에서 재시도
- 서비스 시작 시 poller 자동 실행
- ⚠️ 주의: 멀티 인스턴스 배포 시 동일 PENDING 항목을 여러 인스턴스가 중복 처리할 수 있음. 확장 필요 시 `findOneAndUpdate`를 이용한 원자적 상태 전환(`PENDING → PROCESSING`) 또는 BullMQ 같은 전용 큐 도입을 검토할 것.

### Notification 모듈 - Notify 로직 변경
```
forcedSet = Set(forcedUserIDs)
regularUsers = targetUserIDs.filter(id => !forcedSet.has(id))

enabledIDs = [...forcedUserIDs]  // forced는 무조건 포함

if channelID > 0:
  for uid in regularUsers:
    if pref.IsNotificationEnabled(uid, channelID):
      enabledIDs.append(uid)
else:
  enabledIDs.append(...regularUsers)
```

### Notification 모듈 - CHAT_MESSAGE 알림 포맷
```
title = teamClient.GetName(teamId)         // 실패 시 → 알림 스킵
senderName = userClient.GetDisplayName(authorId)  // 실패 시 → 알림 스킵

nickname ?? name 우선순위로 표시

body = "{senderName}: {content(최대100자)}\n{YYYY-MM-DD HH:MM}"
       // occurredAt을 KST로 변환해서 포맷
```

### FCM data payload (클라이언트 활용용)
```json
{
  "type": "CHAT_MESSAGE",
  "channelId": "42",
  "teamId": "7",
  "authorId": "100"
}
```

### TeamClient (Go)
```
GET {TeamServiceURL}/teams/{teamId}
Response: { "id": 7, "name": "코워크팀", ... }
Timeout: 3s
실패 시: error 반환 → consumer에서 알림 스킵
```

### UserClient (Go)
```
GET {UserServiceURL}/users/{userId}
Response: { "id": 100, "name": "홍길동", "nickname": "길동이", ... }
Timeout: 3s
displayName = nickname if not empty, else name
실패 시: error 반환 → consumer에서 알림 스킵
```

### config.go 추가 env vars
```
TEAM_SERVICE_URL   (또는 team.service-url from config server)
USER_SERVICE_URL   (또는 user.service-url from config server)
```

---

## MongoDB Replica Set 전환 (docker-compose.yml)

```yaml
mongodb:
  image: mongo:8.0
  command: --replSet rs0 --bind_ip_all
  # 기존 설정 유지...

mongodb-init:
  image: mongo:8.0
  depends_on:
    mongodb:
      condition: service_healthy
  restart: "no"
  entrypoint: >
    mongosh --host mongodb -u ${MONGO_ROOT_USERNAME} -p ${MONGO_ROOT_PASSWORD}
    --authenticationDatabase admin
    --eval "try { rs.status() } catch(e) { rs.initiate({ _id: 'rs0', members: [{ _id: 0, host: 'mongodb:27017' }] }) }"
```

NestJS MongooseModule URI에 `?replicaSet=rs0` 추가 필요:
```
mongodb://user:pass@localhost:27017/cowork?replicaSet=rs0&authSource=admin
```

---

## 미결 사항

| 항목 | 내용 |
|------|------|
| 프로젝트 이름 | 프로젝트 API 생기면 title = `{팀 이름} #{프로젝트 이름}` 으로 확장 |
| Outbox 재시도 한도 | 무한 재시도 vs. N회 후 dead-letter 컬렉션 이동 (현재: 무한) |
| 타임존 | KST (UTC+9) 고정으로 시간 포맷 |
| 운영 환경 MongoDB | 실제 운영은 3-node RS 구성 필요 |