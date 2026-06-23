# GitHub PR 보드 백엔드 구현 스펙

## 배경

`team-cowork/cowork-github-app` PR #47에서 GitHub PR 조회/머지/승인 기능이 외부 서비스에 이미 머지되었다. 이 서비스는 두 가지 진입점을 제공한다:

1. Kafka (`github.pr.merge` / `github.pr.approve` 토픽) — 채팅 슬래시 커맨드용. **이번 작업 범위 아님.**
2. HTTP REST API (`X-Internal-Api-Key` 인증) — cowork 백엔드만 호출 가능. **이번 작업이 구현할 소비자 쪽.**

요구사항은 채팅 명령이 아니라 "GitHub 프로젝트 보드" UI — 프로젝트에 연결된 GitHub 레포의 PR을 클릭하면 상세/파일변경목록이 뜨고 머지/승인 버튼이 동작하는 것. PR 목록/보드 UI 자체(프론트엔드)는 범위 제외, **백엔드 API만** 대상.

## 발견된 선행 이슈 (이번 작업에 포함)

`cowork-project`의 `tb_projects` 테이블에는 `github_repo_url` 컬럼이 마이그레이션(`V2__add_github_owner_to_projects.sql`)으로 이미 존재하지만, `Project` 엔티티 / `ProjectDetailResponse` / `Create·UpdateProjectRequest` 어디에도 매핑되어 있지 않다. `cowork-chat`의 `ProjectClient.getGithubRepoInfo()`가 이 필드를 읽어가는 기존 소비자 코드가 있으나 현재는 항상 `null`을 반환하는 상태로 추정된다(레포를 연결하는 API 자체가 없음). 이번 작업에서 엔티티 매핑 + 연결/해제 API를 함께 만든다.

**DB 마이그레이션은 필요 없다** — 컬럼은 이미 존재하므로 엔티티/DTO 매핑만 추가한다.

## 서비스 배치

`cowork-project` (Kotlin/Spring)에 신규 `com.cowork.project.github` 패키지를 만든다. PR은 결국 "프로젝트에 연결된 레포의 PR"이라는 맥락이고, `cowork-project`가 이미 레포 연결 책임을 가지므로 별도 서비스를 만들 이유가 없다.

## 외부 의존성 호출 방식

| 호출 대상 | 같은 모놀레포 Eureka 등록 여부 | 호출 방식 |
|---|---|---|
| `cowork-user` (Elixir, GitHub 사용자명 조회) | 등록됨 (`lib/cowork_user/eureka/registrar.ex`) | OpenFeign `@FeignClient(name = "cowork-user")` — 기존 `cowork-channel`의 `ProjectClient`/`TeamClient` 패턴과 동일 |
| `cowork-github-app` (별도 저장소, 아직 docker-compose/Eureka 미통합) | 미등록 | 고정 URL 환경변수(`GITHUB_APP_SERVICE_URL`) + Spring `RestClient` — 기존 `cowork-channel`의 `OAuthAccountService`가 쓰는 `RestClient.Builder` 패턴과 동일 |

Eureka 미통합은 별도 트랙(인프라/배포)이며 이번 코드 작업의 블로커가 아니다.

## 1. GitHub 레포 연결/해제 (`cowork-project`)

### API

```
PUT    /projects/{projectId}/github-repo   body: { "githubRepoUrl": "https://github.com/owner/repo" }
DELETE /projects/{projectId}/github-repo
```

- 권한: `requireProjectModifier` (프로젝트 OWNER/EDITOR 또는 팀 OWNER/ADMIN) — `updateProject`와 동일 레벨.
- `PUT`은 URL을 파싱해 `host == github.com` 검증, `.git` suffix 제거 후 `github_repo_url` 컬럼에 그대로 저장(소유자/레포를 분리 컬럼으로 저장하지 않음 — 필요할 때마다 파싱). 형식이 유효하지 않으면 `400`.
- `DELETE`는 컬럼을 `null`로 설정.
- `ProjectDetailResponse`에 `githubRepoUrl: String?` 필드를 추가해 프론트가 "이 프로젝트에 PR 보드를 보여줄지" 판단할 수 있게 한다.

### 변경 파일

- `domain/Project.kt`: `githubRepoUrl: String?` 필드 추가 (`@Column(name = "github_repo_url", length = 512)`), `linkGithubRepo(url: String)` / `unlinkGithubRepo()` 도메인 메서드 추가.
- `dto/ProjectDetailResponse.kt`: `githubRepoUrl` 필드 추가.
- `dto/LinkGithubRepoRequest.kt` (신규): `githubRepoUrl: String`.
- `service/ProjectService.kt`: `linkGithubRepo(userId, projectId, request)` / `unlinkGithubRepo(userId, projectId)` 메서드 추가. URL 파싱 로직은 `cowork-chat`의 `ProjectClient.parseRepoUrl`과 동일한 규칙(github.com 호스트만 허용, `.git` suffix 제거)으로 구현.
- `controller/ProjectController.kt`: 위 두 엔드포인트 추가 (프로젝트 리소스의 일부이므로 기존 컨트롤러에 추가, 별도 컨트롤러 불필요).

## 2. PR 상세/파일/머지/승인 프록시 (`cowork-project`)

### API

```
GET  /projects/{projectId}/github/pulls/{prNumber}          — PR 상세
GET  /projects/{projectId}/github/pulls/{prNumber}/files    — 파일 변경 목록
POST /projects/{projectId}/github/pulls/{prNumber}/merge    — 머지
POST /projects/{projectId}/github/pulls/{prNumber}/approve  — 승인
```

- 조회(`GET`) 권한: `requireTeamMember` (`getProject`와 동일 — 팀 멤버면 조회 가능)
- 머지/승인(`POST`) 권한: `requireProjectModifier` (OWNER/EDITOR 또는 팀 OWNER/ADMIN). GitHub 쪽에서도 실제 레포 쓰기 권한(`collaborators/{username}/permission`)을 한 번 더 검증하므로 이중 안전장치가 있어 cowork 쪽은 OWNER로 제한하지 않는다.
- **보안 핵심**: 클라이언트는 `owner`/`repo`를 직접 보내지 않는다. `projectId`로만 받고, 서버가 해당 프로젝트의 `github_repo_url`을 파싱해 owner/repo를 내부적으로 결정한다 (클라이언트가 임의 owner/repo를 넘겨 권한 없는 다른 레포를 조회/머지하는 것을 방지).
- 프로젝트에 연결된 레포가 없으면 `400` ("연결된 GitHub 레포지토리가 없습니다").

### `requesterGithubUsername` 해석

- `cowork-user`의 기존 엔드포인트 `GET /users/{userId}`를 호출 — 이미 응답에 `github_id` 필드 포함(`profile.account.github`). **user-service에 새 API 불필요.**
- `github_id`가 `null`이면(DG 측 확인: GitHub 연동이 필수 값이 아님) `cowork-github-app`을 호출하지 않고 즉시 `400` ("GitHub 계정이 연동되어 있지 않습니다") — 불필요한 외부 호출 방지 + 명확한 에러.

### 응답 DTO (upstream 필드명 그대로 매핑)

```kotlin
// GET pulls/{prNumber}
data class GithubPullRequestResponse(
    val number: Int,
    val title: String,
    val body: String?,
    val author: String,
    val state: String,
    val mergeable: Boolean?,
    val mergeableState: String,
    val reviewDecision: String?, // "APPROVED" | "CHANGES_REQUESTED" | null
    val headRef: String,
    val baseRef: String,
    val htmlUrl: String,
)

// GET pulls/{prNumber}/files
data class GithubPullRequestFileResponse(
    val filename: String,
    val status: String,
    val additions: Int,
    val deletions: Int,
    val patch: String?,
)

// POST pulls/{prNumber}/merge
data class GithubMergeResultResponse(
    val alreadyMerged: Boolean,
    val prUrl: String,
    val prNumber: Int,
)

// POST pulls/{prNumber}/approve
data class GithubApproveResultResponse(
    val prUrl: String,
    val prNumber: Int,
)
```

### upstream(`cowork-github-app`) 계약 (PR #47 코드 기준 확인됨)

- 인증 헤더: `X-Internal-Api-Key` (정확히 이 이름, timing-safe 비교)
- `GET /api/repos/:owner/:repo/pulls/:number` → 위 `GithubPullRequestResponse`와 동일 shape
- `GET .../files` → `GithubPullRequestFileResponse[]`와 동일 shape (단 `patch`는 optional)
- `POST .../merge` body `{ requesterGithubUsername }` → `{ alreadyMerged, prUrl, prNumber }`
- `POST .../approve` body `{ requesterGithubUsername }` → `{ prUrl, prNumber }`

### 에러 매핑

| upstream 상태 | 의미 | cowork-project 처리 |
|---|---|---|
| 404 | PR 없음 | 그대로 `404` 전달 (upstream 메시지 그대로) |
| 403 | 쓰기 권한 없음 / self-review | 그대로 `403` 전달 (upstream 메시지 이미 한국어) |
| 409 | 머지 불가 (mergeable_state 포함 메시지) | 그대로 `409` 전달 |
| 401 | 내부 API 키 불일치 | **그대로 전달하지 않음** — 이건 최종 사용자 잘못이 아니라 우리 쪽 설정 오류이므로 `ERROR` 로그 남기고 `502` + "GitHub 연동 서버 설정에 문제가 있습니다" |
| 5xx / 429 / 타임아웃 / 네트워크 오류 | upstream 장애 | `502` + "GitHub 연동 서버와 통신할 수 없습니다" |

cowork-project 자체 검증 에러:

| 상황 | 응답 |
|---|---|
| 프로젝트에 연결된 GitHub 레포 없음 | `400` |
| 요청자 GitHub 계정 미연동 (`github_id == null`) | `400` |
| `requireTeamMember` / `requireProjectModifier` 실패 | `403` (기존 `ExpectedException` 패턴) |

### 변경 파일

- `client/GithubAppClient.kt` (신규): `RestClient` 기반, `GITHUB_APP_SERVICE_URL` + `INTERNAL_API_KEY`(또는 `GITHUB_APP_INTERNAL_API_KEY` — cowork-project 쪽 환경변수명은 자유, upstream의 `INTERNAL_API_KEY` 변수명을 바꿀 필요는 없음) 사용. 4xx는 본문 메시지까지 파싱해 그대로 던지고, 5xx/네트워크 오류/타임아웃은 별도 예외로 래핑.
- `client/UserClient.kt` (신규): `@FeignClient(name = "cowork-user")`, `GET /users/{userId}` 호출, `githubId: String?` 필드 매핑.
- `service/GithubPullRequestService.kt` (신규): 권한 체크 → 레포 정보 조회 → (머지/승인 시) GitHub 사용자명 조회 및 null 체크 → `GithubAppClient` 호출 → 에러 매핑.
- `controller/GithubPullRequestController.kt` (신규): 위 4개 엔드포인트.
- `dto/GithubPullRequestResponse.kt`, `GithubPullRequestFileResponse.kt`, `GithubMergeResultResponse.kt`, `GithubApproveResultResponse.kt` (신규).

### 권한 체크 헬퍼 공유에 대한 구조 노트

`requireTeamMember` / `requireProjectModifier` / `findProjectOrThrow`는 현재 `ProjectService`의 `private` 메서드다. `GithubPullRequestService`도 동일한 검증이 필요하므로, 이 헬퍼들을 별도 `ProjectAccessGuard` 컴포넌트로 추출해 `ProjectService`와 `GithubPullRequestService`가 함께 의존하도록 한다(권한 로직 중복 방지, 단일 진실 공급원 유지).

## 설정 (환경변수)

`cowork-project`에 추가:

```
GITHUB_APP_SERVICE_URL=http://localhost:3000   # cowork-github-app 고정 주소
GITHUB_APP_INTERNAL_API_KEY=<공유 비밀키>        # upstream의 INTERNAL_API_KEY와 동일한 값
```

운영 키 발급/Vault 등록은 별도 ops 트랙(코드 작업 블로커 아님). **로컬/스테이징용으로 한 번 노출된 키는 폐기 후 재발급 권장.**

## 영향받는 기존 코드 (회귀 확인 필요)

- `cowork-chat`의 `ProjectClient.getGithubRepoInfo()` — 이번 작업으로 `githubRepoUrl`이 실제로 채워지게 되므로, 기존 `github.issue.create` 슬래시 커맨드 기능이 이번 작업 이후 처음으로 실제 동작하게 된다. 회귀 테스트 시 같이 확인.

## 범위 제외

- PR 목록/보드 뷰, diff 렌더링 UI (프론트엔드 영역)
- 채팅 슬래시 커맨드 / Kafka 연동 (`github.pr.merge`/`github.pr.approve` 토픽)
- `cowork-github-app`의 Eureka 등록 및 docker-compose 통합 (별도 작업)
- 운영용 `INTERNAL_API_KEY` 발급 및 Vault 등록 (ops 트랙)
