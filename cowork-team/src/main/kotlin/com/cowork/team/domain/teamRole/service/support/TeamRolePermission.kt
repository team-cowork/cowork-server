package com.cowork.team.domain.teamRole.service.support

import com.cowork.team.domain.teamRole.service.support.TeamRolePermissionChannelType.TEXT
import com.cowork.team.domain.teamRole.service.support.TeamRolePermissionChannelType.VOICE

/**
 * 역할 권한 카탈로그. 역할 기본 권한으로 받을 수 있는 키와 화면에 보여줄 제목·설명의 단일 출처다.
 * [channelTypes]가 비어 있지 않으면 해당 종류의 채널에서 채널별 역할 권한으로 덮어쓸 수 있다.
 * 설명은 각 서비스의 실제 권한 검사와 일치해야 한다.
 */
enum class TeamRolePermission(
    val key: String,
    val group: TeamRolePermissionGroup,
    val title: String,
    val description: String,
    val channelTypes: Set<TeamRolePermissionChannelType> = emptySet(),
) {
    MANAGE_TEAM(
        "manage_team",
        TeamRolePermissionGroup.TEAM,
        "팀 관리",
        "팀 이름, 설명, 아이콘과 팀 설정을 변경할 수 있습니다.",
    ),
    MANAGE_ROLES(
        "manage_roles",
        TeamRolePermissionGroup.TEAM,
        "역할 관리",
        "역할을 만들고 수정·삭제하며 멤버에게 부여하거나 회수할 수 있습니다. 채널과 프로젝트의 역할별 권한도 설정할 수 있습니다. " +
            "자신의 최상위 역할보다 낮은 역할만 다룰 수 있고, 자신에게 없는 권한은 부여할 수 없습니다.",
    ),
    MANAGE_GITHUB(
        "manage_github",
        TeamRolePermissionGroup.TEAM,
        "GitHub 연동 관리",
        "팀에 GitHub App을 연결하거나 해제할 수 있습니다. 볼 수 있는 프로젝트에 저장소를 연결하고 라벨 정책과 웹훅 알림 채널을 설정할 수 있습니다.",
    ),
    MANAGE_NICKNAME_FORMAT(
        "manage_nickname_format",
        TeamRolePermissionGroup.TEAM,
        "닉네임 형식 관리",
        "팀 닉네임 형식의 강제 여부와 예시를 설정할 수 있습니다.",
    ),
    MANAGE_INVITES(
        "manage_invites",
        TeamRolePermissionGroup.MEMBER,
        "멤버 초대",
        "초대 코드를 만들고 조회·삭제하며 멤버를 직접 초대할 수 있습니다.",
    ),
    KICK_MEMBERS(
        "kick_members",
        TeamRolePermissionGroup.MEMBER,
        "멤버 내보내기",
        "자신보다 낮은 역할의 멤버를 팀에서 내보낼 수 있습니다.",
    ),
    MANAGE_NICKNAMES(
        "manage_nicknames",
        TeamRolePermissionGroup.MEMBER,
        "닉네임 관리",
        "자신보다 낮은 역할의 멤버 닉네임을 변경할 수 있습니다.",
    ),
    MANAGE_CHANNELS(
        "manage_channels",
        TeamRolePermissionGroup.CHANNEL,
        "채널 만들기",
        "새 채널을 만들 수 있습니다. 비공개 채널을 만들 때는 자신이 들어갈 수 있도록 채널별 역할 권한을 함께 지정해야 합니다.",
    ),
    CHANNEL_MANAGE(
        "channel_manage",
        TeamRolePermissionGroup.CHANNEL,
        "채널 관리",
        "채널 이름과 설정, 웹훅을 변경하거나 채널을 삭제할 수 있습니다. 다른 사람이 만든 스레드와 공유 계정도 수정할 수 있습니다.",
        setOf(TEXT, VOICE),
    ),
    CHANNEL_REORDER(
        "channel_reorder",
        TeamRolePermissionGroup.CHANNEL,
        "채널 순서 변경",
        "채널 목록에서 채널 순서를 바꿀 수 있습니다. 옮기는 채널마다 이 권한이 있어야 합니다.",
        setOf(TEXT, VOICE),
    ),
    MESSAGE_READ(
        "message_read",
        TeamRolePermissionGroup.TEXT_CHANNEL,
        "채널 보기",
        "텍스트 채널을 보고 메시지를 읽을 수 있습니다. 텍스트 채널에서 이 권한이 없으면 해당 채널의 다른 권한도 사용할 수 없고, " +
            "음성 채널에서는 채팅을 읽지 못합니다. 비공개 텍스트 채널은 채널별 역할 권한으로 허용해야 합니다.",
        setOf(TEXT, VOICE),
    ),
    MESSAGE_SEND(
        "message_send",
        TeamRolePermissionGroup.TEXT_CHANNEL,
        "메시지 보내기",
        "채널에 메시지를 보낼 수 있습니다.",
        setOf(TEXT, VOICE),
    ),
    THREAD_REPLY(
        "thread_reply",
        TeamRolePermissionGroup.TEXT_CHANNEL,
        "스레드에서 답장",
        "스레드를 만들고 메시지에 스레드 답장을 보낼 수 있습니다.",
        setOf(TEXT, VOICE),
    ),
    FILE_ATTACH(
        "file_attach",
        TeamRolePermissionGroup.TEXT_CHANNEL,
        "파일 첨부",
        "메시지에 파일을 첨부할 수 있습니다.",
        setOf(TEXT, VOICE),
    ),
    REACTION_ADD(
        "reaction_add",
        TeamRolePermissionGroup.TEXT_CHANNEL,
        "반응 추가",
        "메시지에 반응을 추가할 수 있습니다.",
        setOf(TEXT, VOICE),
    ),
    MESSAGE_PIN(
        "message_pin",
        TeamRolePermissionGroup.TEXT_CHANNEL,
        "메시지 고정",
        "메시지를 고정하거나 고정을 해제할 수 있습니다.",
        setOf(TEXT, VOICE),
    ),
    MESSAGE_MANAGE(
        "message_manage",
        TeamRolePermissionGroup.TEXT_CHANNEL,
        "메시지 관리",
        "다른 사람의 메시지와 파일을 삭제할 수 있습니다. 다른 사람의 메시지를 수정할 수는 없습니다.",
        setOf(TEXT, VOICE),
    ),
    SLASH_COMMAND_USE(
        "slash_command_use",
        TeamRolePermissionGroup.TEXT_CHANNEL,
        "슬래시 커맨드 사용",
        "채팅에서 슬래시 커맨드를 사용할 수 있습니다.",
        setOf(TEXT, VOICE),
    ),
    GITHUB_ISSUE_CREATE(
        "github_issue_create",
        TeamRolePermissionGroup.TEXT_CHANNEL,
        "GitHub 이슈 만들기",
        "채팅에서 GitHub 이슈를 만들 수 있습니다. 슬래시 커맨드로 만들 때는 슬래시 커맨드 사용 권한도 필요하며, " +
            "대상 프로젝트의 작업 권한이 있어야 합니다.",
        setOf(TEXT, VOICE),
    ),
    SLOWMODE_BYPASS(
        "slowmode_bypass",
        TeamRolePermissionGroup.TEXT_CHANNEL,
        "슬로우 모드 무시",
        "슬로우 모드가 켜진 채널에서도 기다리지 않고 메시지를 보낼 수 있습니다.",
        setOf(TEXT),
    ),
    VOICE_CONNECT(
        "voice_connect",
        TeamRolePermissionGroup.VOICE_CHANNEL,
        "연결",
        "음성 채널에 참가할 수 있습니다. 이 권한이 없으면 해당 채널의 다른 권한도 사용할 수 없습니다. " +
            "비공개 채널은 채널별 역할 권한으로 허용해야 합니다.",
        setOf(VOICE),
    ),
    VOICE_SPEAK(
        "voice_speak",
        TeamRolePermissionGroup.VOICE_CHANNEL,
        "말하기",
        "음성 채널에서 마이크를 켤 수 있습니다.",
        setOf(VOICE),
    ),
    SCREEN_SHARE(
        "screen_share",
        TeamRolePermissionGroup.VOICE_CHANNEL,
        "화면 공유와 카메라",
        "음성 채널에서 화면을 공유하고 카메라를 켤 수 있습니다. 라이브 방송 중에는 화면 공유만 할 수 있습니다.",
        setOf(VOICE),
    ),
    LIVE_BROADCAST(
        "live_broadcast",
        TeamRolePermissionGroup.VOICE_CHANNEL,
        "라이브 방송",
        "음성 채널에서 라이브 방송을 시작하고 진행할 수 있습니다.",
        setOf(VOICE),
    ),
    MANAGE_PROJECTS(
        "manage_projects",
        TeamRolePermissionGroup.PROJECT,
        "프로젝트 만들기와 삭제",
        "프로젝트를 만들고, 볼 수 있는 프로젝트를 삭제할 수 있습니다. 비공개 프로젝트를 만들려면 프로젝트 보기 권한도 필요합니다.",
    ),
    PROJECT_READ(
        "project_read",
        TeamRolePermissionGroup.PROJECT,
        "프로젝트 보기",
        "프로젝트와 연결된 GitHub 이슈·풀 리퀘스트를 보고 댓글을 남길 수 있습니다. 이 권한이 없으면 다른 프로젝트 권한도 사용할 수 없습니다. " +
            "비공개 프로젝트는 프로젝트 역할로 허용해야 합니다.",
    ),
    PROJECT_WRITE(
        "project_write",
        TeamRolePermissionGroup.PROJECT,
        "프로젝트 작업",
        "GitHub 이슈를 만들고 라벨을 바꾸며, 풀 리퀘스트를 승인·병합할 수 있습니다. 다른 사람의 GitHub 댓글도 수정·삭제할 수 있습니다.",
    ),
    PROJECT_MANAGE(
        "project_manage",
        TeamRolePermissionGroup.PROJECT,
        "프로젝트 관리",
        "프로젝트 이름, 설명, 상태, 공개 여부와 프로젝트 설정을 변경할 수 있습니다.",
    ),
    PROJECT_REORDER(
        "project_reorder",
        TeamRolePermissionGroup.PROJECT,
        "프로젝트 순서 변경",
        "프로젝트 목록에서 프로젝트 순서를 바꿀 수 있습니다. 옮기는 프로젝트마다 이 권한이 있어야 합니다.",
    ),
    ;

    companion object {
        val KEYS: Set<String> = entries.mapTo(linkedSetOf()) { it.key }
    }
}
