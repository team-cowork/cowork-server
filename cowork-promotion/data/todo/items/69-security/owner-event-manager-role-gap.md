# 소유 서비스 이벤트의 프로젝트 역할·채널 생성자 정보 부재 해소

- **서비스**: cowork-project, cowork-channel, cowork-preference
- **우선순위**: 🟠 중간
- **현재 상태**: `cowork-preference`는 프로젝트 OWNER·EDITOR 멤버와 채널 생성자를 판단할 수 없어, 두 부류에게는 프로젝트 설정·프로젝트 역할·채널 설정 수정을 허용하지 않고 팀 OWNER·ADMIN에게만 허용한다.

## 문제

`cowork-project`의 `ProjectAccessGuard.requireProjectModifier`는 프로젝트 OWNER·EDITOR 멤버 또는 프로젝트가 속한 팀의
OWNER·ADMIN에게 수정 권한을 주고, `requireProjectOwner`는 프로젝트 OWNER 또는 팀 OWNER·ADMIN에게 준다.
`cowork-channel`의 `ChannelPermissionSupport.requireChannelManager`는 채널 생성자(`createdBy`) 또는 채널이 속한 팀의
OWNER·ADMIN에게 관리 권한을 준다.

`cowork-preference`는 이 판단에 필요한 값을 이벤트로만 받을 수 있다. `project.member.event.v2`의 `ProjectMemberEvent`는
`eventType`·`projectId`·`userId`·`occurredAt`·`snapshot`만 싣고 멤버의 프로젝트 역할(`OWNER`·`EDITOR`·`VIEWER`)이 없다.
`channel.event.v2`의 `ChannelEvent`에는 생성자가 없고, `channel.member.event.v2`의 `role`은 발행 코드가 항상 기본값
`MEMBER`로 채운다.

그 결과 `cowork-preference`의 프로젝트 설정 수정·프로젝트 역할 관리·채널 설정 수정은 소유 서비스보다 좁은 범위인
팀 OWNER·ADMIN에게만 허용된다. 프로젝트 OWNER·EDITOR 멤버나 채널 생성자가 팀 OWNER·ADMIN이 아니면 소유 서비스에서는
수정할 수 있어도 Preference에서는 `403`을 받는다.

## 계약 선택지

| 대상 | 방안 | 비고 |
|---|---|---|
| 프로젝트 역할 | `project.member.event.v2`에 선택 필드 `role`을 추가한다 | 역할 변경은 `publishAdded`로 다시 발행되므로 같은 이벤트에 실을 수 있다. 선택 필드 추가는 버전을 올리지 않아도 된다 |
| 채널 생성자 | `channel.event.v2`에 선택 필드 `createdBy`를 추가한다 | 채널 상태 snapshot과 변경 이벤트가 같은 필드를 가져야 한다 |
| 현행 유지 | 팀 OWNER·ADMIN만 허용하는 제한을 정책으로 확정한다 | 이 경우 이 문서는 정책 결정 기록으로 닫는다 |

## 할 일

- 위 표에서 방안을 정하고 선택한 필드를 소유 서비스의 이벤트와 snapshot에 추가한다.
- `cowork-preference`의 projection이 새 필드를 저장하고 `ProjectAccessGuard`·`ChannelAccessGuard`가 소유 서비스와 같은 수정 권한을 판단하도록 확장한다.
- 새 필드가 도입되기 전에 저장된 projection 행은 같은 version의 snapshot 이벤트로 값을 채우도록 갱신 조건을 추가한다.

## 검증

- 프로젝트 OWNER·EDITOR 멤버, VIEWER 멤버, 채널 생성자, 일반 팀 멤버의 허용·거부를 권한 서비스 단위 테스트로 확인한다.
- 소유 서비스와 Preference의 판단이 같은지 실제 이벤트 흐름으로 확인하는 일은 아직 검증하지 않았다.

## 완료 조건

- 프로젝트 OWNER·EDITOR 멤버와 채널 생성자의 수정 권한이 소유 서비스와 같은 기준으로 Preference에서 판단된다.
- 새 필드를 알 수 없는 projection 행은 수정 권한을 주지 않는다.
