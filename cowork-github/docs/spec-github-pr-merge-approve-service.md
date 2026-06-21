# cowork-github PR 조회/머지/승인 기능 구현 스펙

## 개요

cowork 사용자가 GitHub 웹사이트에 들어가지 않고도, **채팅 명령(Kafka)** 또는 **cowork 웹 페이지(HTTP)** 에서 PR을 조회하고 머지/승인까지 끝낼 수 있도록 하는 기능.

- PR 상세 조회 (제목/본문/작성자/머지 가능 여부/리뷰 상태 + 파일 변경 diff)
- PR 머지 (squash, 브랜치 자동 삭제)
- PR 승인 (Approve review)

기존 Issue 생성 파이프라인(Kafka 전용)과 달리, 조회는 **동기 응답**이 필요하므로 이 서비스에 HTTP REST API를 신규로 추가한다.

---

## 아키텍처

```
                 ┌─────────────────────┐
 채팅(/머지,/승인) │   Kafka Topic        │
 ──────────────► │ github.pr.merge      │──┐
                 │ github.pr.approve    │  │
                 └─────────────────────┘  │
                                            ▼
 cowork 웹 페이지   ┌─────────────────────┐   ┌───────────────────┐
 (PR 상세/머지/승인) │ HTTP REST API        │──►│ PullRequestService │
 ──────────────►  │ (Internal API Key)   │   └────────┬──────────┘
                 └─────────────────────┘            │
                                                      ▼
                                          ┌─────────────────────────┐
                                          │ GithubAuthService        │ (기존, 재사용)
                                          │ GithubApiClient(PR 확장) │
                                          └─────────────────────────┘
                                                      │
                                                      ▼
                                              GitHub REST API
```

**핵심 설계 원칙**: 권한 검증, self-review 차단, GitHub 호출, 멱등성 처리 등 모든 비즈니스 로직은 `PullRequestService` 하나에 모은다. Kafka 컨트롤러와 HTTP 컨트롤러는 진입점(트랜스포트)만 다르고 동일한 서비스 메서드를 호출한다.

---

## 기능 범위 (이번 작업)

| 기능 | 트리거 |
|---|---|
| PR 상세 조회 (mergeable 상태, 리뷰 상태 포함) | HTTP |
| PR 파일 변경 목록 + 전체 diff(patch) | HTTP |
| PR 머지 (squash + 브랜치 자동 삭제) | Kafka + HTTP |
| PR 승인 (Approve) | Kafka + HTTP |

**범위 밖 (Out of scope, 후속 작업)**:
- PR 목록/보드(프로젝트 보드 형태) 뷰 — cowork 프론트엔드 영역
- diff 렌더링 UI — cowork 프론트엔드 영역
- 머지/승인 시 채팅 채널 알림 — 별도 GitHub 웹훅 연동으로 충당 예정이며 이 작업에서 Kafka 알림을 만들지 않음
- Request changes / Comment 리뷰 — Approve만 지원

---

## 트리거 1: Kafka (채팅)

### Topic: `github.pr.merge`

```json
{
  "owner": "my-org",
  "repo": "my-repo",
  "prNumber": 123,
  "requesterGithubUsername": "octocat",
  "channelId": 1,
  "teamId": 1
}
```

### Topic: `github.pr.approve`

```json
{
  "owner": "my-org",
  "repo": "my-repo",
  "prNumber": 123,
  "requesterGithubUsername": "octocat",
  "channelId": 1,
  "teamId": 1
}
```

### 결과 Topic: `github.pr.merge.result` / `github.pr.approve.result`

```ts
interface PullRequestActionResultEvent {
  channelId: number;
  teamId: number;
  success: boolean;
  prNumber: number;
  prUrl?: string;
  error?: string;
}
```

처리 흐름은 기존 `github.issue.create`와 동일한 패턴을 따른다:
- 페이로드 검증 실패 / 4xx(GithubClientError) → 로그 + 결과 이벤트 전송 + 오프셋 커밋(skip)
- 5xx → `process.exit(1)`로 컨테이너 재시작 유도, 오프셋 커밋 안 함

---

## 트리거 2: HTTP REST API (cowork 웹 페이지)

### 인증

서버-투-서버 API 키 방식. cowork 백엔드는 사전에 공유된 키를 헤더로 전달한다.

```
X-Internal-Api-Key: <shared-secret>
```

- 환경 변수 `INTERNAL_API_KEY`로 관리, `AppConfigService`를 통해서만 접근
- 키가 없거나 불일치하면 `401 Unauthorized`
- 이 키는 "cowork 백엔드 자체가 이 서비스를 호출할 자격이 있는지"만 검증한다. "어떤 cowork 사용자가 이 액션을 할 수 있는지"는 아래 GitHub 권한 검증에서 별도로 처리한다.

### 엔드포인트

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/repos/:owner/:repo/pulls/:number` | PR 상세 (상태/mergeable/mergeable_state/리뷰 상태 포함) |
| GET | `/api/repos/:owner/:repo/pulls/:number/files` | 파일 변경 목록 + 전체 patch(diff) 텍스트 |
| POST | `/api/repos/:owner/:repo/pulls/:number/merge` | 머지 실행 |
| POST | `/api/repos/:owner/:repo/pulls/:number/approve` | 승인 실행 |

머지/승인 요청 바디:

```json
{ "requesterGithubUsername": "octocat" }
```

### GET 상세 응답 예시

```json
{
  "number": 123,
  "title": "...",
  "body": "...",
  "author": "octocat",
  "state": "open",
  "mergeable": true,
  "mergeableState": "clean",
  "reviewDecision": "APPROVED",
  "headRef": "feature/foo",
  "baseRef": "main",
  "htmlUrl": "https://github.com/my-org/my-repo/pull/123"
}
```

`mergeableState`는 GitHub의 `mergeable_state` 값(`clean`, `dirty`, `blocked`, `unstable`, `behind` 등)을 그대로 노출한다. 프론트엔드는 이 값으로 머지 버튼 활성/비활성을 판단한다 — 비활성화 로직 자체는 cowork 프론트엔드 책임이며, 이 서비스는 상태값만 정확히 내려준다.

### GET files 응답 예시

```json
[
  {
    "filename": "src/foo.ts",
    "status": "modified",
    "additions": 12,
    "deletions": 3,
    "patch": "@@ -1,3 +1,3 @@\n..."
  }
]
```

GitHub API는 파일이 매우 크거나(다이어그램상 한계) 바이너리인 경우 `patch` 필드를 생략한다 — 이 경우 `patch: null`로 그대로 전달하고, "변경 내용이 너무 커서 표시할 수 없습니다" 같은 처리는 프론트엔드에 위임한다. 파일 목록은 GitHub 기본 페이지네이션(최대 100개/page)을 따르며, 우선 1페이지(100개)까지만 가져온다.

---

## 권한 검증 (GitHub 권한 기준)

cowork는 이미 사용자별 GitHub username을 알고 있으므로, 모든 머지/승인 요청에는 `requesterGithubUsername`이 필수로 포함된다.

검증 절차 (`PullRequestService` 공통 로직):

1. `GET /repos/{owner}/{repo}/collaborators/{username}/permission` 호출 (Installation Token 사용)
2. 응답의 `permission`이 `write` / `maintain` / `admin` 중 하나가 아니면 `403`, 메시지: `"이 저장소에 대한 쓰기 권한이 없습니다."`
3. **승인(approve) 요청**일 때만 추가 검증: PR의 `user.login`과 `requesterGithubUsername`을 대소문자 무시하고 비교 — 같으면 GitHub API를 호출하지 않고 즉시 `403`, 메시지: `"본인이 작성한 PR은 승인할 수 없습니다."` (self-review 사전 차단)

이 권한 검증 로직은 Kafka/HTTP 양쪽 진입점에서 동일하게 호출된다.

---

## 머지 동작 상세

1. `GET /repos/{owner}/{repo}/pulls/{number}`로 현재 상태 조회
2. 이미 `merged === true`이면 **멱등 처리**: 에러 없이 `{ alreadyMerged: true, prUrl }` 형태로 성공 응답 반환 (Kafka 쪽은 `success: true`로 결과 이벤트 전송)
3. 권한 검증 통과 후 `PUT /repos/{owner}/{repo}/pulls/{number}/merge` 호출, `merge_method: "squash"`
4. 머지 성공 시, PR의 `head.repo.full_name === base.repo.full_name`(같은 저장소 브랜치, 포크가 아님)인 경우에만 `DELETE /repos/{owner}/{repo}/git/refs/heads/{branch}` 호출해 브랜치 자동 삭제. 포크에서 온 PR은 삭제 권한이 없으므로 건너뛴다.
5. 브랜치 삭제 실패는 머지 성공 자체에 영향을 주지 않음 — 로그만 남기고 머지 결과는 성공으로 반환

---

## 에러 매핑

| 상황 | HTTP 응답 | 비고 |
|---|---|---|
| PR 없음 | 404 | GitHub 404 그대로 전달 |
| 쓰기 권한 부족 | 403 | 사전 검증에서 차단 |
| self-review(본인 PR 승인) | 403 | 사전 검증에서 차단, GitHub 호출 안 함 |
| 이미 머지됨 (머지 요청 시) | 200 (idempotent) | 에러 아님 |
| merge conflict / 필수 체크 실패 / 필수 리뷰 부족 | 409 | GitHub `405 Method Not Allowed` 응답을 매핑, message에 `mergeable_state` 그대로 포함 |
| Internal API Key 불일치 | 401 | HTTP 진입점만 해당 |
| GitHub 5xx / 네트워크 오류 | 502 | 재시도는 하지 않고 즉시 에러 반환 (HTTP는 동기 응답이라 Kafka처럼 재시도 큐에 못 태움) |

---

## 파일 구조

기존 `GithubModule`에 새 하위 모듈을 등록하는 방식(CLAUDE.md 컨벤션)을 따른다.

```
src/github/pull-request/
  pull-request.module.ts
  pull-request.service.ts          # 핵심 로직: 권한 검증, self-review 차단, 멱등성
  pull-request.controller.ts        # Kafka: github.pr.merge, github.pr.approve
  pull-request-http.controller.ts   # HTTP: /api/repos/:owner/:repo/pulls/...
  dto/
    merge-pull-request.dto.ts
    approve-pull-request.dto.ts
  client/
    pull-request-api.client.ts      # GitHub PR REST 호출 전담 (기존 GithubApiClient와 분리)
  kafka/
    pull-request-result.producer.ts
    event/pull-request-result.event.ts
  guards/
    internal-api-key.guard.ts       # HTTP 전용 인증
```

`PullRequestApiClient`는 기존 `GithubApiClient`와 별도 클래스로 분리한다 (이슈 관련 메서드와 PR 관련 메서드를 한 파일에 섞지 않기 위함). 인증 토큰 발급은 기존 `GithubAuthService`를 그대로 재사용한다.

---

## 환경 변수 추가

```env
INTERNAL_API_KEY=                 # cowork 백엔드 ↔ 이 서비스 간 HTTP 인증 키
```

`AppConfigService`에 `internalApiKey` getter 추가, `env.validation.ts`에 필수 검증 추가.

---

## 테스트 계획

- `pull-request.service.spec.ts`: 권한 검증 분기(write 이상/미만), self-review 차단, 멱등성(이미 머지된 경우), 브랜치 삭제 skip(포크인 경우)
- `pull-request-http.controller.spec.ts`: API 키 누락/불일치 401, 각 엔드포인트 정상 흐름
- `pull-request.controller.spec.ts` (Kafka): 4xx skip, 5xx exit, 결과 이벤트 전송
- `pull-request-api.client.spec.ts`: GitHub REST 호출 매핑, 405→409 변환
- e2e: `POST /api/repos/:owner/:repo/pulls/:number/merge` 정상/권한 없음 케이스

---

## 미해결/후속 확인 필요 사항

- `collaborators/{username}/permission` 호출에 필요한 GitHub App 권한(`Members` 또는 기존 `Pull requests: Read & write`로 충분한지)은 실제 호출 테스트로 확인 필요
- 같은 저장소 내 다른 PR이 동일 브랜치를 참조 중일 때 브랜치 삭제 충돌 가능성은 이번 범위에서 별도 처리하지 않음 (GitHub가 409를 반환하면 로그만 남김)
