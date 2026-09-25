# 채팅 GitHub 이슈 생성 command의 프로젝트 수정 권한 검증

- **서비스**: cowork-chat, cowork-project
- **우선순위**: 🟠 중간
- **현재 상태**: 채팅 슬래시 커맨드는 채널 멤버십과 프로젝트 팀 일치만 확인하고 `github.issue.create`를 발행한다

## 문제

`cowork-project`의 이슈 생성 API(`CreateGithubIssueServiceImpl`)는 `GithubRepoAccessResolver.resolveForModify`로 프로젝트 OWNER·EDITOR 또는 팀 OWNER·ADMIN만 허용한다. 반면 `cowork-chat`의 `ChatService.publishGithubIssueCreateCommand`는 `checkMembershipAndGetTeamId`로 채널 읽기 권한과 채널 팀을 확인하고, 로컬 GitHub 저장소 projection의 팀과 일치하면 바로 `github.issue.create`를 발행한다.

따라서 팀 채널 멤버이면 프로젝트 VIEWER이거나 프로젝트 멤버가 아니어도 채팅으로 이슈 생성 command를 보낼 수 있다. 같은 동작이 진입 경로에 따라 다른 권한 규칙을 적용받는다.

chat만으로는 같은 규칙을 적용할 수 없다. chat의 `project-member-projection`은 `projectId`·`userId`·`deleted`만 저장하고, 원본 `ProjectMemberEvent`에도 role이 없다. 팀 role은 `team-member-projection`에 있다.

## 선택지

| 방식 | 내용 | 고려 사항 |
|---|---|---|
| chat projection에 프로젝트 role 추가 | `project.member.event.v2`에 role을 싣고 chat이 로컬에서 `resolveForModify`와 같은 규칙을 평가한다 | 권한 규칙이 두 서비스에 중복된다. 이벤트 계약 변경과 projection 재구축이 필요하다 |
| project를 거쳐 발행 | chat은 project에 이슈 생성 command를 보내고, project가 권한을 검증한 뒤 `github.issue.create`를 발행한다 | 권한 규칙이 한 곳에 남는다. chat이 결과를 받는 경로(`github.issue.result`)와 거부 응답 전달 방식을 정해야 한다 |

## 할 일

- 두 방식 중 하나를 선택하고 권한 규칙의 소유 위치를 정한다.
- 선택한 방식으로 채팅 경로에 프로젝트 OWNER·EDITOR 또는 팀 OWNER·ADMIN 검사를 적용한다.
- 권한이 없을 때 슬래시 커맨드 요청자에게 거부를 전달하는 방식을 정한다.

## 검증

- 프로젝트 VIEWER, 프로젝트 비멤버, 프로젝트 EDITOR, 팀 ADMIN 각각에 대한 권한 판단 단위 테스트를 작성한다.

## 완료 조건

- 채팅 슬래시 커맨드와 project API가 같은 권한 규칙으로 이슈 생성을 허용한다.
- 프로젝트 수정 권한이 없는 사용자의 이슈 생성 command는 발행되지 않는다.
