# cowork-github GitHub Issue Service 구현 스펙

## 개요

Kafka 이벤트를 수신해 GitHub App 인증을 거쳐 GitHub Issue를 생성하는 NestJS Hybrid App.  
DB 없음. 순수 이벤트 파이프라인: **Kafka → GitHub App 인증 → GitHub API 호출**.

---

## 아키텍처

```
Kafka Topic: github.issue.create
       │
       ▼
GithubController (@EventPattern)
       │
       ▼
IssueService (재시도 로직 포함)
       │
       ├─► GithubAuthService (JWT → InstallationToken, Redis 캐시)
       │
       └─► GithubApiClient (HTTP → GitHub REST API)
```

---

## 앱 실행 모드

- **Hybrid App**: HTTP 서버(포트 `process.env.PORT ?? 3000`)와 Kafka Consumer 동시 실행
- HTTP는 헬스체크 전용 (`GET /health → { status: 'ok' }`)
- Kafka는 `@EventPattern` 기반 메시지 수신

---

## Kafka 설정

| 항목 | 값 |
|---|---|
| Topic | `github.issue.create` |
| Consumer Group ID | 환경변수 `KAFKA_GROUP_ID` |
| Broker | 환경변수 `KAFKA_BROKERS` (콤마 구분, 예: `localhost:9092`) |
| Offset Commit | 수동 커밋 (`autoCommit: false`) — GitHub API 성공 후 커밋 |

### 메시지 페이로드 스키마

```json
{
  "owner": "my-org",
  "repo": "my-repo",
  "title": "Bug: ...",
  "body": "## Description\n...",
  "labels": ["bug"],
  "assignees": ["user1"]
}
```

- `owner`, `repo`, `title`: 필수 (string)
- `body`: 선택 (string)
- `labels`: 선택 (string[])
- `assignees`: 선택 (string[])

---

## GitHub App 인증 흐름

1. `GITHUB_APP_ID` + `GITHUB_PRIVATE_KEY`(Base64 디코딩 후 PEM)로 RS256 JWT 생성 (만료 10분)
2. JWT로 GitHub API `GET /orgs/{owner}/installation` 호출 → `installationId` 획득
3. `installationId`로 `POST /app/installations/{id}/access_tokens` → Installation Token 발급
4. Redis에 `github:token:{installationId}` 키로 TTL 저장
5. `github:installation:{owner}` 키와 메모리 캐시로 Installation ID를 캐싱

### Redis 캐시 전략

- 라이브러리: **ioredis**
- 키:
  - `github:token:{installationId}`
  - `github:installation:{owner}`
- TTL:
  - 토큰: 기본 **3300초 (55분)**
  - Installation ID: 기본 **86400초 (24시간)**
- 캐시 히트 시 토큰 바로 반환, 미스 시 신규 발급 후 저장

---

## 재시도 전략 (지수 백오프)

GitHub API 호출 실패 시:

| 시도 | 대기 시간 |
|---|---|
| 1회 재시도 | 1초 |
| 2회 재시도 | 2초 |
| 3회 재시도 | 4초 |
| 3회 모두 실패 | 에러 로그 후 Kafka 오프셋 **커밋하지 않음** (재처리 가능) |

- 재시도 대상: 5xx 에러, 네트워크 타임아웃
- 재시도 비대상: 4xx 에러 (잘못된 페이로드) → 에러 로그 후 오프셋 커밋하여 스킵

---

## 에러 로깅

실패 시 다음 컨텍스트를 포함해 `Logger.error()` 출력:

```
GitHub API failed: { owner, repo, title, attempt, error.message }
```

---

## 환경 변수

```env
# HTTP
PORT=3000

# Kafka
KAFKA_BROKERS=localhost:9092
KAFKA_GROUP_ID=cowork-github-group

# GitHub App
GITHUB_APP_ID=123456
GITHUB_PRIVATE_KEY=<Base64 인코딩된 PEM>

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# Cache / Retry
GITHUB_TOKEN_CACHE_TTL_SECONDS=3300
GITHUB_INSTALLATION_CACHE_TTL_SECONDS=86400
GITHUB_ISSUE_MAX_RETRIES=3
```

> `GITHUB_PRIVATE_KEY`: `base64 -i private-key.pem` 출력값을 그대로 사용

---

## 파일 구조 및 구현 명세

### 파일 생성 순서

```
src/main.ts
src/app.module.ts
src/github/github.module.ts
src/github/dto/create-issue.dto.ts
src/github/auth/github-auth.service.ts
src/github/client/github-api.client.ts
src/github/issue/issue.service.ts
src/github/github.controller.ts
```

---

### `src/main.ts`

- `NestFactory.create(AppModule)` 으로 HTTP 앱 생성
- `app.connectMicroservice()` 로 Kafka 마이크로서비스 연결
  ```ts
  transport: Transport.KAFKA,
  options: {
    client: { brokers: process.env.KAFKA_BROKERS.split(',') },
    consumer: { groupId: process.env.KAFKA_GROUP_ID },
    consumer: { allowAutoTopicCreation: false },
  }
  ```
- `app.startAllMicroservices()` 후 `app.listen(PORT)`
- `ValidationPipe({ whitelist: true, forbidNonWhitelisted: true })` 전역 등록

---

### `src/app.module.ts`

imports:
- `ConfigModule.forRoot({ isGlobal: true, validationSchema: Joi.object(...) })`
- `GithubModule`
- Redis 연결을 위한 커스텀 `RedisModule` (또는 GithubModule 내부에서 ioredis 직접 주입)

---

### `src/app.controller.ts`

- `GET /health` → `{ status: 'ok' }` 반환
- 기존 Hello World 제거

---

### `src/github/github.module.ts`

providers:
- `GithubAuthService`
- `GithubApiClient`
- `IssueService`
- ioredis 인스턴스 (커스텀 프로바이더, 토큰 상수: `REDIS_CLIENT`)

controllers:
- `GithubController`

---

### `src/github/dto/create-issue.dto.ts`

```ts
export class CreateIssueDto {
  @IsString() @IsNotEmpty() owner: string;
  @IsString() @IsNotEmpty() repo: string;
  @IsString() @IsNotEmpty() title: string;
  @IsString() @IsOptional() body?: string;
  @IsArray() @IsString({ each: true }) @IsOptional() labels?: string[];
  @IsArray() @IsString({ each: true }) @IsOptional() assignees?: string[];
}
```

---

### `src/github/auth/github-auth.service.ts`

책임:
1. `getInstallationToken(owner: string): Promise<string>`
2. Redis + 메모리 캐시 조회
3. 캐시 미스 시:
   - RS256 JWT 생성 (`jsonwebtoken` 라이브러리)
   - `GET /orgs/{owner}/installation` 호출 → `installationId`
   - `POST /app/installations/{installationId}/access_tokens` 호출 → token
   - Redis에 설정된 TTL로 저장
4. 캐시 히트 시 바로 반환

---

### `src/github/client/github-api.client.ts`

책임:
1. `createIssue(token: string, dto: CreateIssueDto): Promise<{ number: number; html_url: string }>`
2. GitHub REST API `POST /repos/{owner}/{repo}/issues` 호출
3. HTTP 클라이언트: NestJS `HttpModule` + `HttpService`
4. Authorization 헤더: `Bearer {token}`

---

### `src/github/issue/issue.service.ts`

책임:
1. `createIssue(dto: CreateIssueDto): Promise<void>`
2. 지수 백오프 재시도 로직 (기본 3회, 환경 변수로 조정 가능)
3. `GithubAuthService.getInstallationToken(dto.owner)` 호출
4. `GithubApiClient.createIssue(token, dto)` 호출
5. 5xx/네트워크 오류: 재시도
6. 4xx 오류: 에러 로그 후 즉시 반환 (오프셋 스킵 처리 위임)
7. 실패 시 로그: `{ owner, repo, title, attempt, error.message }`

---

### `src/github/github.controller.ts`

```ts
@Controller()
export class GithubController {
  @EventPattern('github.issue.create')
  async handleIssueCreate(
    @Payload() dto: CreateIssueDto,
    @Ctx() context: KafkaContext,
  ) {
    // ValidationPipe로 DTO 검증
    // IssueService.createIssue(dto) 호출
    // 성공/4xx 실패 후 context.getMessage()로 수동 커밋
    // 5xx 실패(재시도 소진) 시 커밋 안 함 (Kafka 재처리 유도)
  }
}
```

---

## 설치 패키지

```bash
npm install @nestjs/microservices kafkajs
npm install @nestjs/config
npm install ioredis
npm install jsonwebtoken
npm install axios @nestjs/axios
npm install class-validator class-transformer

npm install -D @types/jsonwebtoken
```

---

## `.env.example`

```env
PORT=3000

KAFKA_BROKERS=localhost:9092
KAFKA_GROUP_ID=cowork-github-group

GITHUB_APP_ID=
GITHUB_PRIVATE_KEY=

REDIS_HOST=localhost
REDIS_PORT=6379
```

---

## 테스트 업데이트 방침

- `app.controller.spec.ts`: Hello World 테스트 → `/health` 테스트로 교체
- `github-auth.service.spec.ts`: Redis mock + jsonwebtoken mock으로 단위 테스트
- `issue.service.spec.ts`: 재시도 로직 테스트 (3회 실패 시나리오 포함)
- e2e 테스트: Hybrid App 구동 후 `GET /health` 200 확인
