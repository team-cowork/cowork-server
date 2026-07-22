# cowork-chat

## 역할
채팅 서비스.
- Socket.io(`/chat` 네임스페이스) 기반 실시간 메시지 송수신·타이핑 알림
- 채널 메시지 CRUD, 고정, 이모지 반응, 스레드(답글)
- MinIO presigned URL 기반 첨부파일 업로드, `FILE_SHARE` 채널 파일 목록 조회
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
- MinIO — 첨부파일 저장

## 포트 & 진입점
- 포트: `8087`
- 서비스 내부 전역 prefix `/chat` (단, `/health`, `/metrics`는 제외)
- 외부 REST 요청은 Gateway의 `/api/**`를 사용하며, Gateway가 chat 전용 경로에 `/chat` prefix를 붙인다.
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
| 통합 검색     | `POST /graphql` (`unifiedSearch`)                          | 메시지(Elasticsearch)+채널(channel-service) 병렬 검색         |

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
- Consume: `chat.message`, `github.issue.result`, `channel.event`, `project.event`, `channel.member.event`(멤버십 동기화)

## 의존 서비스
- HTTP: `cowork-channel`, `cowork-user`, `cowork-project` (표시 이름·채널 정보·프로젝트 멤버십 조회)
- MongoDB, Elasticsearch(검색 색인), Redis(차단 목록·레이트리밋), MinIO(첨부파일)
- Discord Webhook(선택) — 알림/에러 알림

## 환경 변수

| 공급원 | 설정 |
|---|---|
| Compose | `APP_CONFIG_URL`, `APP_PROFILE` |
| Config Server | 포트, MongoDB 옵션, Elasticsearch, Kafka, Redis, Eureka, 서비스 URL, MinIO endpoint/정책, rate limit |
| Vault | `MONGODB_URI`, `JWT_SECRET`, Discord webhook, MinIO access/secret key |

Config Server가 내려준 값은 비어 있는 `process.env`에만 채워지므로 직접 환경변수가 최우선입니다. Compose 기동에서는 Config Server 조회 실패 시 즉시 종료합니다.
