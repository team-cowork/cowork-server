# GitHub App 연동 아키텍처

`cowork`이 GitHub과 연동되는 전체 흐름 정리. 실제 GitHub API 호출은 이 모노레포 밖에 있는 별도 **github-app** 서비스(GitHub App 자격으로 GitHub REST API를 대신 호출하는 중계 서비스)를 통해서만 이루어진다.

## 구성 요소

| 서비스 | 역할 |
|---|---|
| `cowork-team` | GitHub App 설치 URL 발급, 설치/해제를 Kafka로 전파 |
| `cowork-project` | 레포 연동 관리, 이슈/PR/댓글/라벨 조회·조작 (github-app을 동기 HTTP로 호출) |
| `cowork-chat` | 슬래시 커맨드로 이슈 생성 요청, GitHub 웹훅 이벤트를 채팅 메시지로 브로드캐스트 |
| github-app (외부) | 실제 GitHub REST API 호출, GitHub 웹훅 수신 — 이 리포에 없음 |

## 1. 설치 연동 (cowork-team → cowork-project)

1. `cowork-team`의 `GenerateGithubInstallUrlService`가 HMAC 서명된 state를 포함한 GitHub App 설치 URL을 발급한다 (`TeamGithubController`).
2. 설치/해제가 완료되면 `cowork-team`이 Kafka `team.github.connected` / `team.github.disconnected` 토픽으로 이벤트를 발행한다 (`TeamGithubEventPublisher`).
3. `cowork-project`의 `TeamGithubInstallationConsumer`가 이를 소비해 `TeamGithubInstallation` 엔티티에 반영한다.
4. state 서명 검증에는 `TEAM_GITHUB_STATE_SECRET`을 쓰며, `cowork-team`과 `cowork-project`에 동일한 값이 배포되어야 한다 (`TeamGithubProperties`).

## 2. project → github-app: 동기 REST 경로 (기본 경로)

`cowork-project`가 프론트에 노출하는 REST API는 모두 `GithubAppClient`(Feign, `github-app.service-url`)를 **동기 HTTP**로 호출한다.

| 컨트롤러 | 경로 | 내용 |
|---|---|---|
| `ProjectGithubOrgRepoController` | `GET /projects/{projectId}/github/repos` | 팀이 연결한 GitHub 조직의 레포 목록 |
| `ProjectGithubRepoController` | `/projects/{projectId}/github-repos` | 레포 등록/해제, 웹훅 알림 채널 설정 |
| `GithubIssueBoardController` | `/projects/{projectId}/github-repos/{repoId}/issues` | 열린 이슈 목록, 이슈 생성, 라벨 목록 |
| `GithubIssueController` | `.../issues/{issueNumber}` | 라벨 교체, 이슈 댓글 CRUD |
| `GithubPullRequestBoardController` | `/projects/{projectId}/github-repos/{repoId}/pulls` | 열린 PR 보드(Draft/리뷰중) |
| `GithubPullRequestController` | `.../pulls/{prNumber}` | PR 상세/파일, 머지, 승인, PR 댓글 CRUD |

권한 체크는 `GithubRepoAccessResolver`가 담당한다.

- `resolveForRead`: 팀 멤버 이상이면 조회 가능 (`ProjectAccessGuard.requireTeamMember`)
- `resolveForModify`: 프로젝트 OWNER/EDITOR, 또는 팀 OWNER/ADMIN만 쓰기 가능 (`ProjectAccessGuard.requireProjectModifier`)

호출 실패 처리는 `GithubAppCallExecutor`(네트워크/타임아웃 → 502)와 `GithubAppErrorDecoder`(4xx는 그대로 전달, 그 외는 502)가 담당한다. 재시도·서킷브레이커는 없다. 커넥션 풀링 관련 조사는 [`feign-hc5-pooling.md`](./feign-hc5-pooling.md) 참고.

## 3. chat → github-app: 비동기 Kafka 경로 (슬래시 커맨드, 예외적 경로)

이슈 생성만 예외적으로 `cowork-project`를 거치지 않고 `cowork-chat`이 직접 github-app과 Kafka로 통신한다.

1. 사용자가 채팅에서 `github.issue.create` 슬래시 커맨드를 입력하면 `ChatService.publishGithubIssueCreateCommand`가 채널-프로젝트 팀 일치 여부만 확인한다 (`checkMembershipAndGetTeamId` — **팀 멤버 여부만 확인, `resolveForModify` 같은 프로젝트 수정 권한 체크는 하지 않음**).
2. `GithubIssueProducer`가 Kafka `github.issue.create` 토픽으로 이벤트를 발행한다. 이 토픽을 소비하는 컨슈머는 이 모노레포 안에 없다 — github-app이 직접 구독해 GitHub API를 호출하는 것으로 추정된다.
3. github-app이 처리 결과를 `github.issue.result` 토픽으로 발행하면 `GithubIssueResultConsumer`가 이를 채팅 SYSTEM 메시지로 저장·브로드캐스트한다.

> **알려진 갭**: 이 경로는 project의 `resolveForModify` 권한 모델(OWNER/EDITOR만 쓰기 가능)을 우회한다. 팀 멤버라면 프로젝트에 VIEWER로만 등록됐거나 프로젝트 멤버가 아니어도 채팅으로는 이슈를 생성할 수 있다. 별도로 다뤄야 하는 이슈로 남겨둔다.

## 4. GitHub 웹훅 → chat 알림 경로

1. github-app이 GitHub 웹훅(push/issues/pull_request)을 수신하면 Kafka `github.repo.event` 토픽으로 발행한다.
2. `cowork-chat`의 `GithubRepoEventConsumer`가 이를 소비하고, `owner`/`repo`에 연결된 알림 대상을 조회하기 위해 `cowork-project`의 **내부 전용** 엔드포인트를 HTTP로 호출한다.
   - `GET /internal/projects/github-webhook-target?owner=&repo=` (`InternalProjectGithubController`)
   - `/internal/**` 경로는 Gateway 라우트 테이블에 없어 외부에서 도달할 수 없고, Eureka로 서비스 간 직접 호출된다.
3. 알림 채널이 설정된 대상마다(0개 이상, 여러 팀이 같은 레포를 연결할 수 있음) SYSTEM 메시지로 저장하고 Socket.IO로 브로드캐스트한다.

## 5. 댓글 작성 시 부가 알림 (github-app과 무관)

`CreateGithubCommentServiceImpl`이 댓글을 github-app에 동기로 생성한 뒤, 부모 이슈/PR 작성자가 cowork 사용자면 `GithubCommentNotificationPublisher`가 Kafka `notification.trigger` 토픽으로 알림을 발행한다. 이건 github-app과 무관한 **cowork 내부 알림 파이프라인**(`cowork-notification`, Go)이다 — 위 3, 4번의 GitHub 관련 Kafka 토픽과 혼동하지 않는다.

## API 명세 노출 관련 주의

`InternalProjectGithubController`는 Swagger 어노테이션(`@Tag`, `@Operation`, `@ApiResponses`)이 이미 붙어 있지만, `application.yml`의 `sdk.swagger.paths-to-match`가 원래 `/projects/**`만 포함해 `/internal/**` 경로가 생성된 OpenAPI 스펙에서 누락되어 있었다. `paths-to-match`에 `/internal/**`를 추가해 해결했다 — 외부에서 실제로 호출 가능한 경로가 아니므로 스펙에 노출해도 접근 범위에는 영향이 없다.
