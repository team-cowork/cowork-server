# GitHub App 연동 아키텍처

`cowork`이 GitHub과 연동되는 전체 흐름 정리. 실제 GitHub API 호출은 이 모노레포 밖에 있는 별도 **github-app** 서비스(GitHub App 자격으로 GitHub REST API를 대신 호출하는 중계 서비스)를 통해서만 이루어진다.

## 구성 요소

| 서비스 | 역할 |
|---|---|
| `cowork-team` | GitHub App 설치 URL 발급, 설치/해제 상태를 `team.lifecycle`로 전파 |
| `cowork-project` | 레포 연동 관리, GitHub 조회·댓글·라벨은 동기 HTTP, 이슈 생성·PR 머지·승인은 Kafka command로 처리 |
| `cowork-chat` | 슬래시 커맨드로 이슈 생성 요청, GitHub 웹훅 이벤트를 채팅 메시지로 브로드캐스트 |
| github-app (외부) | 실제 GitHub REST API 호출, GitHub 웹훅 수신 — 이 리포에 없음 |

## 1. 설치 연동 (cowork-team → cowork-project)

1. `cowork-team`의 `GenerateGithubInstallUrlService`가 HMAC 서명된 state를 포함한 GitHub App 설치 URL을 발급한다 (`TeamGithubController`).
2. 외부 github-app의 설치/해제 action을 `cowork-team`이 소비해 authoritative team 상태와 outbox를 같은 transaction으로 갱신한다.
3. `cowork-team`은 설치 ID·조직 정보를 포함한 compacted `team.lifecycle` 상태를 발행한다.
4. `cowork-project`의 `TeamLifecycleConsumer`가 버전·tombstone·설치 ID 소유권 fence를 적용해 `TeamGithubInstallation` projection을 갱신한다. 오래된 snapshot이나 순서가 뒤바뀐 해제 event는 최신 소유권을 되돌리지 못한다.
5. `TEAM_GITHUB_STATE_SECRET`은 설치 URL callback state를 검증하는 `cowork-team` 전용 설정이다.

외부 github-app이 보내는 `team.github.connected`와 `team.github.disconnected`는 서로 다른 topic이며
공통 source revision이 없다. 따라서 외부 action 자체가 역순으로 도착하면 team consumer만으로 원래 순서를
복원할 수 없다. installation별 versioned full-state topic 또는 두 event의 공통 revision·reconciliation을
외부 producer에 추가하기 전까지는 알려진 일관성 갭이다. 위 `team.lifecycle` version/fence는 team이 수락한
상태 이후의 downstream 역전을 막는 계약이지 이 ingress 갭을 고치는 계약은 아니다.

## 2. project → github-app: 외부 provider HTTP adapter

`GithubAppClient`(Feign, `github-app.service-url`)는 다른 cowork 서비스가 소유한 durable state를
조회하는 client가 아니라 GitHub 원본에 접근하는 adapter다. 조직 저장소와 PR의 현재 상태는 완전한
versioned event feed로 제공되지 않으므로, Kafka request/reply나 불완전한 local projection으로 바꾸지 않고
request-scoped HTTP 조회로 남긴다. 이슈 생성·PR 머지·승인에는 이 client를 사용하지 않는다.

직전 PR #284의 명시적 보류 범위는 이슈 생성·PR 머지·승인 세 API뿐이다. 외부
[`cowork-github-app/main` c5bfbb2](https://github.com/team-cowork/cowork-github-app/commit/c5bfbb2f89250ca73efbd2555851c97d4a6de17d)와
대조하면 아래 HTTP 계약 중 조직 저장소 조회와 PR 목록·상세·파일 조회 4개만
구현되어 있다. 이슈·댓글·라벨 관련 9개 호출은 대응 HTTP route도 Kafka command/result·멱등 계약도 없어
404가 발생한다. 댓글·라벨 쓰기는 Kafka command로 전환 가능한 유형이지만, 이 저장소의 producer만 먼저
바꾸면 처리 consumer가 없는 메시지를 성공으로 접수하게 된다. 따라서 이는 일관성을 이유로 인정한 영구
HTTP 예외가 아니라 외부 github-app 계약을 함께 변경해야 하는 보류 갭이다.

| 컨트롤러 | 경로 | 내용 | 외부 계약 상태 |
|---|---|---|---|
| `ProjectGithubOrgRepoController` | `GET /projects/{projectId}/github/repos` | 팀이 연결한 GitHub 조직의 레포 목록 | HTTP 구현됨 |
| `ProjectGithubRepoController` | `/projects/{projectId}/github-repos` | 레포 등록/해제, 웹훅 알림 채널 설정 | project 로컬 상태, 외부 HTTP 없음 |
| `GithubIssueBoardController` | `/projects/{projectId}/github-repos/{repoId}/issues` | 열린 이슈·라벨 조회; 이슈 생성은 Kafka command | 조회 HTTP 미구현 |
| `GithubIssueController` | `.../issues/{issueNumber}` | 라벨 교체, 이슈 댓글 CRUD | HTTP·Kafka 계약 미구현 |
| `GithubPullRequestBoardController` | `/projects/{projectId}/github-repos/{repoId}/pulls` | 열린 PR 보드(Draft/리뷰중) | HTTP 구현됨 |
| `GithubPullRequestController` | `.../pulls/{prNumber}` | PR 상세·파일·댓글 CRUD; 머지·승인은 Kafka command | PR 조회 구현, 댓글 HTTP 미구현 |

권한 체크는 `GithubRepoAccessResolver`가 담당한다.

- `resolveForRead`: 팀 멤버 이상이면 조회 가능 (`ProjectAccessGuard.requireTeamMember`)
- `resolveForModify`: 프로젝트 OWNER/EDITOR, 또는 팀 OWNER/ADMIN만 쓰기 가능 (`ProjectAccessGuard.requireProjectModifier`)

호출 실패 처리는 `GithubAppCallExecutor`(네트워크/타임아웃 → 502)와 `GithubAppErrorDecoder`(4xx는 그대로 전달, 그 외는 502)가 담당한다. 재시도·서킷브레이커는 없다. 커넥션 풀링 관련 조사는 [`feign-hc5-pooling.md`](./feign-hc5-pooling.md) 참고.

## 3. project/chat → github-app: 비동기 Kafka command

`cowork-project`의 이슈 생성·PR 머지·승인 API는 각각 `github.issue.create`, `github.pr.merge`,
`github.pr.approve`를 발행하고 broker 적재 성공 뒤 `202 Accepted`를 반환한다. 처리 결과를 project에
동기로 회신하지 않는 fire-and-forget 계약이다.

채팅 슬래시 커맨드도 `github.issue.create`를 사용한다.

1. 사용자가 채팅에서 `github.issue.create` 슬래시 커맨드를 입력하면 `ChatService.publishGithubIssueCreateCommand`가 채널-프로젝트 팀 일치 여부만 확인한다 (`checkMembershipAndGetTeamId` — **팀 멤버 여부만 확인, `resolveForModify` 같은 프로젝트 수정 권한 체크는 하지 않음**).
2. `GithubIssueProducer`가 Kafka `github.issue.create` 토픽으로 이벤트를 발행한다. 이 토픽을 소비하는 컨슈머는 이 모노레포 안에 없다 — github-app이 직접 구독해 GitHub API를 호출하는 것으로 추정된다.
3. github-app이 처리 결과를 `github.issue.result` 토픽으로 발행하면 `GithubIssueResultConsumer`가 이를 채팅 SYSTEM 메시지로 저장·브로드캐스트한다.

> **알려진 갭**: 이 경로는 project의 `resolveForModify` 권한 모델(OWNER/EDITOR만 쓰기 가능)을 우회한다. 팀 멤버라면 프로젝트에 VIEWER로만 등록됐거나 프로젝트 멤버가 아니어도 채팅으로는 이슈를 생성할 수 있다. 별도로 다뤄야 하는 이슈로 남겨둔다.

## 4. GitHub 웹훅 → chat 알림 경로

1. github-app이 GitHub 웹훅(push/issues/pull_request)을 수신하면 Kafka `github.repo.event` 토픽으로 발행한다.
2. `cowork-project`는 저장소 연결·해제와 webhook 채널 변경을 DB와 transactional outbox에 함께 기록하고, repo ID를 key로 하는 compacted `project.github-repo.event`를 발행한다. startup과 5분 주기 full snapshot 및 partition completion marker도 같은 계약으로 제공한다.
3. `cowork-chat`은 `project.github-repo.event`를 MongoDB projection으로 소비하고, 모든 partition의 snapshot marker와 초기 high-watermark를 통과하기 전에는 readiness를 열지 않는다. 이후에도 현재 broker high-watermark와 checkpoint가 어긋나면 readiness를 다시 닫는다.
4. `GithubRepoEventConsumer`는 `github.repo.event`를 받을 때 로컬 projection에서 `owner`/`repo` 알림 대상을 조회한다. 대상마다(0개 이상, 여러 팀이 같은 레포를 연결할 수 있음) SYSTEM 메시지로 저장하고 Socket.IO로 브로드캐스트한다.

## 5. 댓글 작성 시 부가 알림 (github-app과 무관)

`CreateGithubCommentServiceImpl`이 댓글을 github-app에 동기로 생성한 뒤, 부모 이슈/PR 작성자가 cowork 사용자면 `GithubCommentNotificationPublisher`가 Kafka `notification.trigger` 토픽으로 알림을 발행한다. 이건 github-app과 무관한 **cowork 내부 알림 파이프라인**(`cowork-notification`, Go)이다 — 위 3, 4번의 GitHub 관련 Kafka 토픽과 혼동하지 않는다.

## API 명세 노출 관련 주의

project의 GitHub App 전용 내부 HTTP 조회 API는 모두 제거했다. webhook target은
`project.github-repo.event`의 full state·tombstone·snapshot으로 전달하고, label policy는
`preference.github-repo.setting.state`를 별도로 projection한다. OpenAPI도 `/internal/**`를 노출하지 않는다.
