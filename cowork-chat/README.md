# cowork-chat

## 역할
채팅 서비스.
- Socket.io(`/chat` 네임스페이스) 기반 실시간 메시지 송수신·타이핑 알림
- 채널 메시지 CRUD, 고정, 이모지 반응, 스레드(답글)
- SeaweedFS(S3 호환) presigned URL 기반 첨부파일 업로드, `FILE_SHARE` 채널 파일 목록 조회
- DM 목록/숨기기, 사용자 차단(Redis)
- 팀 단위 미읽 메시지 카운트
- Elasticsearch 기반 메시지 전문 검색(프로젝트/팀 단위) 및 GraphQL 통합 검색(메시지+채널)
- GitHub 이슈 생성 슬래시 커맨드(Kafka 비동기 발행)
- Kafka 아웃박스 패턴 기반 알림 트리거 발송

## 스택
- NestJS 11 + TypeScript
- Socket.io, GraphQL(Apollo) — 통합 검색
- MongoDB (Mongoose)
- KafkaJS
- Elasticsearch, Redis(ioredis) — 검색 색인, 차단 목록·레이트리밋
- SeaweedFS(S3 호환) + `@aws-sdk/client-s3`/`@aws-sdk/s3-request-presigner` — 첨부파일 저장

## 포트 & 진입점
- 포트: `8087`
- 서비스 내부 전역 prefix `/chat` (단, `/health`, `/metrics`는 제외)
- 외부 Gateway 경로 정책은 `docs/gateway-config.md`를 기준으로 한다.
- Swagger UI: `/api` (JSON: `/api-json`)
- Prometheus 메트릭: `/metrics`

## API
전체 스펙은 Swagger 참고. 주요 리소스 그룹:

| 그룹          | 경로                                                       | 설명                                                          |
|---------------|------------------------------------------------------------|---------------------------------------------------------------|
| 메시지        | `/channels/:channelId/messages`, `/pins`, `/reactions`     | 전송(Kafka 비동기)·수정·삭제·고정·반응                        |
| 파일          | `/channels/:channelId/files*`                              | presigned URL 발급·업로드 확인·파일 목록(`FILE_SHARE` 전용)   |
| 슬래시 커맨드 | `/channels/:channelId/slash-commands`                      | `github.issue.create` 등 (기존 `/github/issues`는 deprecated) |
| DM            | `/dms`                                                     | 내 DM 목록 조회, 대화 숨기기                                  |
| 차단          | `/block/:targetUserId`                                     | 사용자 차단·해제·목록 조회                                    |
| 미읽 카운트   | `/teams/:teamId/unread`                                    | 팀 내 채널별 미읽 수                                          |
| 검색          | `/search/messages`, `/projects/:projectId/messages/search` | Elasticsearch 전문 검색 (nori 형태소 분석 + fuzzy)            |
| 통합 검색     | `POST /graphql` (`unifiedSearch`)                          | 메시지(Elasticsearch)+Kafka 채널 projection 병렬 검색         |

### WebSocket (`/chat` namespace, Gateway path `/ws/chat`)
| 이벤트                                                                       | 방향 | 설명                                                 |
|------------------------------------------------------------------------------|------|------------------------------------------------------|
| `join` / `leave`                                                             | C→S  | 채널 room 참가/퇴장                                  |
| `join:team` / `leave:team`                                                   | C→S  | 팀 room 참가/퇴장 (채널/프로젝트 변경 이벤트 수신용) |
| `typing:start` / `typing:stop`                                               | C→S  | 타이핑 상태 (Redis 레이트리밋 적용)                  |
| `message`                                                                    | S→C  | 새 메시지 수신                                       |
| `message:edited` / `message:deleted` / `message:pinned` / `message:unpinned` | S→C  | 메시지 상태 변경 브로드캐스트                        |
| `message:reaction:added` / `message:reaction:removed`                        | S→C  | 메시지 이모지 반응 변경                              |
| `channel:unread:updated`                                                     | S→C  | 채널 미읽 수 변경                                    |
| `member:joined` / `member:left` / `member:role:updated`                      | S→C  | 채널 멤버십 변경                                     |
| `channel:created` / `channel:updated` / `channel:deleted`                    | S→C  | 팀 채널 변경                                         |
| `project:created` / `project:updated` / `project:deleted`                    | S→C  | 팀 프로젝트 변경                                     |
| `typing`                                                                     | S→C  | 같은 채널 참여자에게 타이핑 상태 릴레이              |
| `error` / `exception`                                                        | S→C  | 요청·연결 인증 실패                                  |

전체 이벤트 명세는 `public/asyncapi.json`을 참고한다. 메시지 작성 자체는 WebSocket client event가 아니라 REST 요청 → `chat.message` Kafka 처리 흐름이다.

## 인증
- REST: 모든 요청은 Gateway가 주입한 `X-User-Id`/`X-User-Role` 헤더로 식별한다(`AuthGuard`가 전역 적용). `@Public()`으로 인증 예외, `@Roles()`로 역할 제한, `@UserId()`/`@UserRole()` 데코레이터로 컨트롤러에 값 주입.
- WebSocket: 운영 진입점은 Gateway의 `/ws/chat`이다. Gateway가 `cowork_ws_token` 쿠키 또는 `Authorization` 헤더의 JWT를 검증하고 주입한 `X-User-Id`/`X-User-Role`을 사용한다. 현재 `ChatGateway.resolveIdentity`에는 Gateway 헤더가 없는 로컬 개발 연결을 위한 `auth.token` fallback이 남아 있지만, 이 직접 검증 경로를 운영에 노출해서는 안 된다. 식별에 실패하면 `exception` 이벤트를 보낸 뒤 연결을 종료한다.

## Mongoose 스키마 컨벤션 (`src/**/schema/*.schema.ts`)
- 모든 최상위 도큐먼트 스키마는 `@Schema({ timestamps: true, versionKey: false })`로 `createdAt`/`updatedAt`을 자동 관리하고 `__v`를 제거한다. 클라이언트 응답에는 `_id`를 유지한다.
- 서브도큐먼트(`Attachment`, `EditHistory`, `Reaction` 등)는 `@Schema({ _id: false })`로 불필요한 `_id` 생성을 막는다.
- 문서 타입은 `HydratedDocument<T>`로 export한다 (예: `MessageDocument`, `ChannelMemberDocument`).
- 인덱스는 `SchemaFactory.createForClass()` 이후 `Schema.index()` 호출로 정의한다(데코레이터 인덱스 미사용).
- 스키마 설명 문서는 `schema/` 디렉토리에 둔다(`schema/message.schema.md`).

## Kafka 토픽
- Produce: `chat.message`(전송 요청 발행 → 자기 자신이 consume해 저장·브로드캐스트), `notification.trigger`(아웃박스 폴러), `github.issue.create`
- Consume: `chat.message`, `github.issue.result`, `channel.event`(채널 projection), `project.event`, `channel.member.event`(채널 멤버십 projection), `project.member.event`(프로젝트 멤버십 projection), `team.member.event`(팀 멤버십 projection), `user.profile.event`(사용자 프로필 projection)

### 내부 조회 projection

일반 채팅 경로는 다른 서비스의 REST API를 호출하지 않는다. MongoDB의 `channelprojections`, `channelmembers`,
`projectprojections`, `projectmemberprojections`, `teammemberprojections`, `userprofileprojections` 컬렉션을 조회한다. 각 projection은
표시용 `sourceOccurredAt`, ordering용 epoch-nanoseconds BSON Long `sourceVersion`, `deleted` tombstone을 보존해 중복·역순 이벤트를
안전하게 처리한다. 같은 nanosecond이면 DELETE가 우선하며, 같은 millisecond 안의 DELETE→재가입도 원문 fraction으로 구분한다.
GitHub App 연동에 필요한 프로젝트/GitHub 조회는 이번 전환 범위에서 제외되어 HTTP를 유지한다.

| 토픽 | key | 상태 이벤트 |
|---|---|---|
| `channel.event` | `channelId` | `CREATED`, `UPDATED`, `DELETED` |
| `channel.member.event` | `channelId:userId` | `JOIN`, `LEAVE` |
| `project.event` | `projectId` | `CREATED`, `UPDATED`, `DELETED` |
| `project.member.event` | `projectId:userId` | `ADDED`, `REMOVED` |
| `team.member.event` | `teamId:userId` | `UPSERT`, `DELETE` |
| `user.profile.event` | `userId` | `UPSERT`, `DELETE` |

로컬과 운영의 빈 DB에서도 복구할 수 있도록 `channel.event`, `channel.member.event`,
`project.member.event`, `team.member.event`, `user.profile.event` producer는 현재 상태 전체를 startup/주기 snapshot으로 발행해야 한다.
토픽은 엔티티 키(`channelId`, `channelId:userId`, `projectId:userId`, `teamId:userId`, `userId`) 기준 compact 정책을 사용하고,
consumer는 `fromBeginning: true`로 replay한다. 새 환경은 snapshot 발행 완료와 consumer lag 0을 확인한 뒤
트래픽을 연다. producer는 snapshot outbox 뒤에 `__cowork_projection_snapshot_complete__:{partition}` marker를 각
partition에 명시적으로 발행한다. chat readiness는 모든 partition의 marker receipt와 next offset을 같은 Mongo checkpoint
update로 저장하고, startup high-watermark와 marker를 모두 지난 뒤에만 열린다. 따라서 신규 빈 topic도 snapshot 전에 ready로
오판하지 않는다. 계약/JSON 오류는 원문을 `projection_quarantine_records`에 먼저 영속화한 뒤 checkpoint를 전진하며,
Mongo 저장 실패 같은 런타임 오류는 offset을 유지해 재시도한다. 운영에서 projection DB를 복원하거나 비우는 경우 consumer
group offset도 함께 초기화해야 한다.
모든 이벤트의 `occurredAt`은 UTC offset이 포함된 ISO-8601 값이어야 한다. 변경 이벤트는 원본 row의 변경 시각을 사용하고,
snapshot은 원본 변경 시각 또는 일관된 조회를 시작하기 전에 캡처한 watermark를 사용한다. snapshot을 읽은 뒤의 발행 시각을
새로 찍으면 지연된 snapshot이 더 최신인 것으로 오인될 수 있으므로 사용하지 않는다.
`channel.event`, `project.event`, `channel.member.event`의 startup/주기 snapshot은 `snapshot: true`를 포함해야 한다.
consumer는 projection 상태는 반영하되 Redis 무효화나 Socket 변경 알림 같은 실시간 부수효과는 발생시키지 않는다.

## 의존 서비스
- HTTP: `cowork-project` (GitHub App 연동 조회만 유지)
- Kafka projection producer: `cowork-channel`, `cowork-project`, `cowork-team`, `cowork-user`
- MongoDB, Elasticsearch(검색 색인), Redis(차단 목록·레이트리밋), SeaweedFS(첨부파일)
- Discord Webhook(선택) — 알림/에러 알림

## 환경 변수

| 공급원 | 설정 |
|---|---|
| Compose | `APP_CONFIG_URL`, `APP_PROFILE` |
| Config Server | 포트, MongoDB 옵션, Elasticsearch, Kafka, Redis, Eureka, 서비스 URL, S3(SeaweedFS) endpoint/정책, rate limit |
| Vault | `MONGODB_URI`, `JWT_SECRET`, Discord webhook, S3(SeaweedFS) access/secret key |

Config Server가 내려준 값은 비어 있는 `process.env`에만 채워지므로 직접 환경변수가 최우선입니다. Compose 기동에서는 Config Server 조회 실패 시 즉시 종료합니다.
