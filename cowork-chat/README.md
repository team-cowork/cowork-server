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
- 전역 prefix `chat` (단, `/health`, `/metrics`는 제외)
- Swagger UI: `/api` (JSON: `/api-json`)
- Prometheus 메트릭: `/metrics`

## API
전체 스펙은 Swagger 참고. 주요 리소스 그룹:

| 그룹 | 경로 | 설명 |
|---|---|---|
| 메시지 | `/channels/:channelId/messages`, `/pins`, `/reactions` | 전송(Kafka 비동기)·수정·삭제·고정·반응 |
| 파일 | `/channels/:channelId/files*` | presigned URL 발급·업로드 확인·파일 목록(`FILE_SHARE` 전용) |
| 슬래시 커맨드 | `/channels/:channelId/slash-commands` | `github.issue.create` 등 (기존 `/github/issues`는 deprecated) |
| DM | `/dms` | 내 DM 목록 조회, 대화 숨기기 |
| 차단 | `/block/:targetUserId` | 사용자 차단·해제·목록 조회 |
| 미읽 카운트 | `/teams/:teamId/unread` | 팀 내 채널별 미읽 수 |
| 검색 | `/search/messages`, `/projects/:projectId/messages/search` | Elasticsearch 전문 검색 (nori 형태소 분석 + fuzzy) |
| 통합 검색 | `POST /graphql` (`unifiedSearch`) | 메시지(Elasticsearch)+채널(channel-service) 병렬 검색 |

### WebSocket (`/chat` 네임스페이스, path `/chat-ws`)
| 이벤트 | 방향 | 설명 |
|---|---|---|
| `join` / `leave` | C→S | 채널 room 참가/퇴장 |
| `join:team` / `leave:team` | C→S | 팀 room 참가/퇴장 (채널/프로젝트 변경 이벤트 수신용) |
| `typing:start` / `typing:stop` | C→S | 타이핑 상태 (Redis 레이트리밋 적용) |
| `message` | S→C | 새 메시지 수신 |
| `message:edited` / `message:deleted` / `message:pinned` / `message:unpinned` | S→C | 메시지 상태 변경 브로드캐스트 |
| `typing` | S→C | 같은 채널 참여자에게 타이핑 상태 릴레이 |
| `exception` | S→C | 연결 인증 실패 |

## 인증
- REST: 모든 요청은 Gateway가 주입한 `X-User-Id`/`X-User-Role` 헤더로 식별한다(`AuthGuard`가 전역 적용). `@Public()`으로 인증 예외, `@Roles()`로 역할 제한, `@UserId()`/`@UserRole()` 데코레이터로 컨트롤러에 값 주입.
- WebSocket: Gateway가 `chat-ws` 핸드셰이크 요청의 `cowork_ws_token` 쿠키(`cowork-authorization`이 로그인/리프레시 시 발급)에서 JWT를 검증해 주입한 `X-User-Id`/`X-User-Role` 헤더를 우선 신뢰한다. 브라우저의 WebSocket 업그레이드 요청은 커스텀 `Authorization` 헤더를 실을 수 없어, REST와 달리 쿠키로 토큰을 전달한다. 헤더가 없는 경우(Gateway 미경유 등)에 한해 Socket.IO handshake의 `auth.token`에 담긴 JWT를 `JwtService`로 직접 검증하는 방식으로 대체한다(`ChatGateway.resolveIdentity`). 두 방식 모두 실패 시 `exception` 이벤트 emit 후 연결 종료.

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

## 환경변수
| 변수 | 필수 | 설명 |
|---|---|---|
| `PORT` | O | 리슨 포트 |
| `EUREKA_SERVER_URL` / `EUREKA_INSTANCE_HOST` | O | Eureka 등록 정보 |
| `MONGODB_URI` | O | MongoDB 연결 URI |
| `MONGODB_SERVER_SELECTION_TIMEOUT_MS` / `MONGODB_CONNECT_TIMEOUT_MS` / `MONGODB_DIRECT_CONNECTION` | - | MongoDB 연결 옵션 (기본값 있음) |
| `KAFKA_BOOTSTRAP_SERVERS` | O | Kafka 브로커 주소(CSV) |
| `ELASTICSEARCH_URL` | O | Elasticsearch 엔드포인트 |
| `REDIS_HOST` | O | Redis 호스트 (차단 목록, 레이트리밋 공용) |
| `REDIS_PORT` | - | Redis 포트 (기본 6379) |
| `JWT_SECRET` | O | WebSocket handshake JWT 검증용 |
| `CHANNEL_SERVICE_URL` | O | cowork-channel URL |
| `USER_SERVICE_URL` | O | cowork-user URL |
| `PROJECT_SERVICE_URL` | O | cowork-project URL |
| `MINIO_INTERNAL_ENDPOINT` | O | MinIO 내부 엔드포인트 |
| `MINIO_ACCESS_KEY` | O | MinIO 액세스 키 |
| `MINIO_SECRET_KEY` | O | MinIO 시크릿 키 |
| `MINIO_BUCKET` | O | MinIO 버킷명 |
| `MINIO_PUBLIC_ENDPOINT` / `MINIO_PUBLIC_BASE_URL` | - | 첨부파일 공개 URL용 (기본: 내부 엔드포인트) |
| `DISCORD_WEBHOOK_URL` | - | 에러/이벤트 알림용 Discord Webhook |
| `CHAT_MESSAGE_RATE_LIMIT_WINDOW_MS` / `CHAT_MESSAGE_RATE_LIMIT_MAX_REQUESTS` | - | 메시지 전송 레이트리밋 |
| `CHAT_TYPING_RATE_LIMIT_WINDOW_MS` / `CHAT_TYPING_RATE_LIMIT_MAX_REQUESTS` | - | 타이핑 이벤트 레이트리밋 |
