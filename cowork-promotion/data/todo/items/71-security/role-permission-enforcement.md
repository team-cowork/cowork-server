# 역할 권한 키 강제 적용

- **서비스**: cowork-chat, cowork-channel, cowork-team, cowork-project, cowork-voice, cowork-preference
- **우선순위**: 🟠 중간
- **현재 상태**: 역할 기본 권한과 채널별 권한 덮어쓰기는 저장되고 v2 topic으로 전파되지만, 실제 요청을 거부하는 곳은 `manage_roles`와 채널 정책의 `message_read`뿐이다.

## 문제

`cowork-preference`는 역할 기본 권한(`tb_team_role_definitions.permissions`)을 `PermissionKeys.ROLE_SCOPED`의
키별 boolean 전체 상태로, 채널별 권한 덮어쓰기(`tb_channel_role_policies.permissions`)를 `PermissionKeys.CHANNEL_SCOPED`
키의 명시적 허용·거부로 저장한다. 덮어쓰기에 없는 키는 역할 기본 권한을 따른다는 규칙으로 저장 계약을 정했다.
두 상태는 `preference.team-role.state.v2`와 `preference.channel-role-policy.state.v2`로 team·channel·chat에 전파된다.

강제 적용은 그대로다. `cowork-team`의 `TeamRoleAccessGuard.requireManageRoles`와 `cowork-preference`의
`TeamRoleCommandProcessor`만 `manage_roles`를 읽는다. 메시지 읽기는 `cowork-preference`의
`ChannelRolePolicyRepository.canReadMessages`, `cowork-channel`의 `ChannelMessageReadPolicyEvaluator`,
`cowork-chat`의 `ChannelMessageReadAccessService`가 채널 정책의 `message_read`만으로 판단한다. 적용 가능한 정책이 없으면
역할 기본 권한으로 넘어가지 않고 거부한다. 나머지 키는 저장만 되고 요청을 거부하지 않는다.

권한 데이터도 서비스마다 다르게 들어와 있다. `cowork-chat`의 team role projection은 `permissions` 전체를 저장하고
`cowork-channel`의 `TeamRoleProjection`은 priority만 저장한다. `cowork-project`와 `cowork-voice`는 team role이나 채널 정책
projection을 받지 않는다.

## 결정할 정책

| 항목 | 선택지 | 비고 |
|------|--------|------|
| 사용자 정의 역할이 없는 팀 멤버 | 모두 거부 / 기본 허용 집합 / Discord `@everyone` 같은 팀 기본 역할 도입 | 지금 `message_read`는 거부한다. `message_send` 등에 같은 규칙을 쓰면 역할이 없는 멤버는 아무것도 할 수 없다 |
| built-in ADMIN | 채널 정책 우회 / OWNER만 우회 | 지금 채널 정책은 OWNER만 우회하고, `manage_roles`는 OWNER·ADMIN이 우회한다 |
| 역할 기본 권한 합산 | 하나라도 `true`면 허용(Discord 방식) / priority 최상위 역할만 | 채널 덮어쓰기는 최상위 priority, 같은 priority면 거부 우선으로 이미 정했다 |
| 기존 생성자·소유자 판단과의 관계 | 권한 키로 대체 / 기존 판단과 OR | `ChannelPermissionSupport.requireChannelManager`는 채널 생성자 또는 팀 OWNER·ADMIN을 허용한다 |

## 할 일

### 공통 평가 규칙

- 키별 판정 순서(OWNER 우회 → 채널 덮어쓰기 최상위 priority, 같은 priority면 거부 우선 → 역할 기본 권한 → 기본값)를 확정한다.
- 확정한 규칙을 `message_read` 평가 세 곳(`canReadMessages`, `ChannelMessageReadPolicyEvaluator`, `ChannelMessageReadAccessService`)에 같은 의미로 적용한다.
- `cowork-channel`의 `TeamRoleProjection`에 `permissions`를 저장한다.

### 채팅 (`cowork-chat`)

- `chat.controller.ts`의 `POST messages`에 `message_send`를, `parentMessageId`가 있는 전송에 `thread_reply`를 적용한다.
- `files/presigned-url`·`files/confirm`에 `file_attach`, `messages/:messageId/reactions`에 `reaction_add`, `pins/:messageId`에 `message_pin`을 적용한다.
- 다른 사용자의 메시지 삭제에 `message_manage`, `slash-commands`에 `slash_command_use`, `github/issues`에 `github_issue_create`를 적용한다.

### 채널 (`cowork-channel`)

- 채널 수정·웹훅·회의록 템플릿 관리에 `channel_manage`, 채널 멤버 추가·제거에 `channel_member_manage`를 적용한다.
- 팀 채널 생성·삭제·정렬에 `manage_channels`를 적용한다.

### 팀·프로젝트 (`cowork-team`, `cowork-project`)

- 팀 정보·아이콘 수정에 `manage_team`, 초대 코드 생성·삭제에 `manage_invites`, 멤버 내보내기에 `kick_members`, GitHub App 연동에 `manage_github`를 적용한다.
- `cowork-project`가 team role projection을 받게 하고 프로젝트 생성·삭제·정렬에 `manage_projects`를 적용한다.

### 음성 (`cowork-voice`)

- `cowork-voice`가 team role과 채널 정책 projection을 받게 한다.
- `voice_connect`를 음성 참가에, `voice_speak`·`screen_share`를 LiveKit token의 `VideoGrant` 발행 권한(`internal/infra/livekit/service.go`)에, `live_broadcast`를 `/live/channels/{channel_id}/start`에 적용한다.

### Preference

- 텍스트·음성 채널 설정 수정 권한(`ChannelAccessGuard.requireSettingsManager`)에 `channel_manage`를 반영할지 결정하고 적용한다.

## 검증

- 키별로 허용·거부·덮어쓰기 없음(역할 기본 권한 사용) 세 경우의 단위 테스트를 각 평가 지점에 추가한다.
- 같은 priority의 허용·거부 충돌과 OWNER 우회를 세 `message_read` 평가 지점에서 같은 결과로 확인한다.
- projection 준비 전 요청이 `503`을 반환하는지 확인한다.

## 완료 조건

- `PermissionKeys.ROLE_SCOPED`의 모든 키가 대응하는 API에서 평가되고, 권한이 없는 요청은 `403`으로 거부된다.
- `message_read` 판정은 세 서비스에서 같은 규칙으로 계산되며 역할 기본 권한까지 반영되어 있다.
- 강제 적용에 필요한 team role·채널 정책 projection을 각 서비스가 보유하고 있다.
