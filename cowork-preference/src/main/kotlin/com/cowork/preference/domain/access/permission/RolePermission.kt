package com.cowork.preference.domain.access.permission

import com.cowork.preference.domain.access.permission.RolePermissionChannelType.TEXT
import com.cowork.preference.domain.access.permission.RolePermissionChannelType.VOICE
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.util.Locale

/**
 * 역할 권한 카탈로그. PermissionKeys의 권한을 화면에 보여줄 제목·설명과 연결한다.
 * [channelTypes]가 비어 있지 않으면 해당 종류의 채널에서 채널별 역할 권한으로 덮어쓸 수 있다.
 * 설명은 각 서비스의 실제 권한 검사와 일치해야 한다.
 */
enum class RolePermission(
    val key: String,
    val group: RolePermissionGroup,
    val title: String,
    val description: String,
    val channelTypes: Set<RolePermissionChannelType> = emptySet(),
) {
    ADMINISTRATOR(
        PermissionKeys.ADMINISTRATOR,
        RolePermissionGroup.TEAM,
        "관리자",
        "소유권 이양을 제외한 팀 작업을 허용하고 채널·프로젝트별 권한 제한을 우회합니다.",
    ),
    CREATE_TEAM(
        PermissionKeys.CREATE_TEAM,
        RolePermissionGroup.TEAM,
        "팀 생성",
        "새 팀 생성 허용",
    ),
    DELETE_TEAM(
        PermissionKeys.DELETE_TEAM,
        RolePermissionGroup.TEAM,
        "팀 삭제",
        "팀과 팀의 멤버십을 삭제할 수 있습니다.",
    ),
    TRANSFER_TEAM_OWNERSHIP(
        PermissionKeys.TRANSFER_TEAM_OWNERSHIP,
        RolePermissionGroup.TEAM,
        "소유권 이양",
        "현재 소유자가 다른 팀 멤버에게 소유권을 이양할 수 있습니다. 소유자 정책에만 부여됩니다.",
    ),
    MANAGE_TEAM(
        PermissionKeys.MANAGE_TEAM,
        RolePermissionGroup.TEAM,
        "팀 관리",
        "팀 이름, 설명, 아이콘과 팀 설정을 변경할 수 있습니다.",
    ),
    MANAGE_ROLES(
        PermissionKeys.MANAGE_ROLES,
        RolePermissionGroup.TEAM,
        "역할 관리",
        "역할을 만들고 수정·삭제하며 멤버에게 부여하거나 회수할 수 있습니다. 채널과 프로젝트의 역할별 권한도 설정할 수 있습니다. " +
            "자신의 최상위 역할보다 낮은 역할만 다룰 수 있고, 자신에게 없는 권한은 부여할 수 없습니다.",
    ),
    MANAGE_GITHUB(
        PermissionKeys.MANAGE_GITHUB,
        RolePermissionGroup.TEAM,
        "GitHub 연동 관리",
        "팀에 GitHub App을 연결하거나 해제할 수 있습니다. 볼 수 있는 프로젝트에 저장소를 연결하고 라벨 정책과 웹훅 알림 채널을 설정할 수 있습니다.",
    ),
    MANAGE_NICKNAME_FORMAT(
        PermissionKeys.MANAGE_NICKNAME_FORMAT,
        RolePermissionGroup.TEAM,
        "닉네임 형식 관리",
        "팀 닉네임 형식의 강제 여부와 예시를 설정할 수 있습니다.",
    ),
    MANAGE_INVITES(
        PermissionKeys.MANAGE_INVITES,
        RolePermissionGroup.MEMBER,
        "멤버 초대",
        "초대 코드를 만들고 조회·삭제하며 멤버를 직접 초대할 수 있습니다.",
    ),
    KICK_MEMBERS(
        PermissionKeys.KICK_MEMBERS,
        RolePermissionGroup.MEMBER,
        "멤버 내보내기",
        "자신보다 낮은 역할의 멤버를 팀에서 내보낼 수 있습니다.",
    ),
    MANAGE_NICKNAMES(
        PermissionKeys.MANAGE_NICKNAMES,
        RolePermissionGroup.MEMBER,
        "닉네임 관리",
        "자신보다 낮은 역할의 멤버 닉네임을 변경할 수 있습니다.",
    ),
    MANAGE_CHANNELS(
        PermissionKeys.MANAGE_CHANNELS,
        RolePermissionGroup.CHANNEL,
        "채널 만들기",
        "새 채널을 만들 수 있습니다. 비공개 채널을 만들 때는 자신이 들어갈 수 있도록 채널별 역할 권한을 함께 지정해야 합니다.",
    ),
    CHANNEL_MANAGE(
        PermissionKeys.CHANNEL_MANAGE,
        RolePermissionGroup.CHANNEL,
        "채널 관리",
        "채널 이름과 설정, 웹훅을 변경하거나 채널을 삭제할 수 있습니다. 다른 사람이 만든 스레드와 공유 계정도 수정할 수 있습니다.",
        setOf(TEXT, VOICE),
    ),
    CHANNEL_REORDER(
        PermissionKeys.CHANNEL_REORDER,
        RolePermissionGroup.CHANNEL,
        "채널 순서 변경",
        "채널 목록에서 채널 순서를 바꿀 수 있습니다. 옮기는 채널마다 이 권한이 있어야 합니다.",
        setOf(TEXT, VOICE),
    ),
    ARCHIVE_CHANNELS(
        PermissionKeys.ARCHIVE_CHANNELS,
        RolePermissionGroup.CHANNEL,
        "채널 보관",
        "프로젝트에 속하지 않은 텍스트 채널을 보관하거나 복원할 수 있습니다. 보관된 채널은 읽기 전용이 되며, 볼 수 있는 채널만 보관할 수 있습니다. " +
            "프로젝트 채널은 프로젝트 채널 보관 권한을 따릅니다.",
    ),
    MESSAGE_READ(
        PermissionKeys.MESSAGE_READ,
        RolePermissionGroup.TEXT_CHANNEL,
        "채널 보기",
        "텍스트 채널을 보고 메시지를 읽을 수 있습니다. 텍스트 채널에서 이 권한이 없으면 해당 채널의 다른 권한도 사용할 수 없고, " +
            "음성 채널에서는 채팅을 읽지 못합니다. 비공개 텍스트 채널은 채널별 역할 권한으로 허용해야 합니다.",
        setOf(TEXT, VOICE),
    ),
    MESSAGE_SEND(
        PermissionKeys.MESSAGE_SEND,
        RolePermissionGroup.TEXT_CHANNEL,
        "메시지 보내기",
        "채널에 메시지를 보낼 수 있습니다.",
        setOf(TEXT, VOICE),
    ),
    THREAD_REPLY(
        PermissionKeys.THREAD_REPLY,
        RolePermissionGroup.TEXT_CHANNEL,
        "스레드에서 답장",
        "스레드를 만들고 메시지에 스레드 답장을 보낼 수 있습니다.",
        setOf(TEXT, VOICE),
    ),
    FILE_ATTACH(
        PermissionKeys.FILE_ATTACH,
        RolePermissionGroup.TEXT_CHANNEL,
        "파일 첨부",
        "메시지에 파일을 첨부할 수 있습니다.",
        setOf(TEXT, VOICE),
    ),
    REACTION_ADD(
        PermissionKeys.REACTION_ADD,
        RolePermissionGroup.TEXT_CHANNEL,
        "반응 추가",
        "메시지에 반응을 추가할 수 있습니다.",
        setOf(TEXT, VOICE),
    ),
    MESSAGE_PIN(
        PermissionKeys.MESSAGE_PIN,
        RolePermissionGroup.TEXT_CHANNEL,
        "메시지 고정",
        "메시지를 고정하거나 고정을 해제할 수 있습니다.",
        setOf(TEXT, VOICE),
    ),
    MESSAGE_MANAGE(
        PermissionKeys.MESSAGE_MANAGE,
        RolePermissionGroup.TEXT_CHANNEL,
        "메시지 관리",
        "다른 사람의 메시지와 파일을 삭제할 수 있습니다. 다른 사람의 메시지를 수정할 수는 없습니다.",
        setOf(TEXT, VOICE),
    ),
    SLASH_COMMAND_USE(
        PermissionKeys.SLASH_COMMAND_USE,
        RolePermissionGroup.TEXT_CHANNEL,
        "슬래시 커맨드 사용",
        "채팅에서 슬래시 커맨드를 사용할 수 있습니다.",
        setOf(TEXT, VOICE),
    ),
    GITHUB_ISSUE_CREATE(
        PermissionKeys.GITHUB_ISSUE_CREATE,
        RolePermissionGroup.TEXT_CHANNEL,
        "GitHub 이슈 만들기",
        "채팅에서 GitHub 이슈를 만들 수 있습니다. 슬래시 커맨드로 만들 때는 슬래시 커맨드 사용 권한도 필요하며, " +
            "대상 프로젝트의 작업 권한이 있어야 합니다.",
        setOf(TEXT, VOICE),
    ),
    SLOWMODE_BYPASS(
        PermissionKeys.SLOWMODE_BYPASS,
        RolePermissionGroup.TEXT_CHANNEL,
        "슬로우 모드 무시",
        "슬로우 모드가 켜진 채널에서도 기다리지 않고 메시지를 보낼 수 있습니다.",
        setOf(TEXT),
    ),
    VOICE_CONNECT(
        PermissionKeys.VOICE_CONNECT,
        RolePermissionGroup.VOICE_CHANNEL,
        "연결",
        "음성 채널에 참가할 수 있습니다. 이 권한이 없으면 해당 채널의 다른 권한도 사용할 수 없습니다. " +
            "비공개 채널은 채널별 역할 권한으로 허용해야 합니다.",
        setOf(VOICE),
    ),
    VOICE_SPEAK(
        PermissionKeys.VOICE_SPEAK,
        RolePermissionGroup.VOICE_CHANNEL,
        "말하기",
        "음성 채널에서 마이크를 켤 수 있습니다.",
        setOf(VOICE),
    ),
    SCREEN_SHARE(
        PermissionKeys.SCREEN_SHARE,
        RolePermissionGroup.VOICE_CHANNEL,
        "화면 공유와 카메라",
        "음성 채널에서 화면을 공유하고 카메라를 켤 수 있습니다. 라이브 방송 중에는 화면 공유만 할 수 있습니다.",
        setOf(VOICE),
    ),
    LIVE_BROADCAST(
        PermissionKeys.LIVE_BROADCAST,
        RolePermissionGroup.VOICE_CHANNEL,
        "라이브 방송",
        "음성 채널에서 라이브 방송을 시작하고 진행할 수 있습니다.",
        setOf(VOICE),
    ),
    VOICE_LOG_READ(
        PermissionKeys.VOICE_LOG_READ,
        RolePermissionGroup.VOICE_CHANNEL,
        "음성 채널 로그 보기",
        "음성 채널의 통화 시작·종료, 입장·퇴장, 라이브 시작·종료 기록을 볼 수 있습니다. 채널 보기 권한도 있어야 합니다. " +
            "이 권한이 없으면 음성 채널의 시스템 메시지가 조회·실시간 수신과 안 읽은 메시지 수에서 빠집니다.",
        setOf(VOICE),
    ),
    MANAGE_PROJECTS(
        PermissionKeys.MANAGE_PROJECTS,
        RolePermissionGroup.PROJECT,
        "프로젝트 만들기와 삭제",
        "프로젝트를 만들고, 볼 수 있는 프로젝트를 삭제할 수 있습니다. 비공개 프로젝트를 만들려면 프로젝트 보기 권한도 필요합니다.",
    ),
    PROJECT_READ(
        PermissionKeys.PROJECT_READ,
        RolePermissionGroup.PROJECT,
        "프로젝트 보기",
        "프로젝트를 보고, 연결된 GitHub 이슈·풀 리퀘스트를 조회하거나 댓글을 남길 수 있습니다. 이 권한이 없으면 다른 프로젝트 권한도 사용할 수 없습니다. " +
            "비공개 프로젝트는 프로젝트 역할로 허용해야 합니다.",
    ),
    PROJECT_WRITE(
        PermissionKeys.PROJECT_WRITE,
        RolePermissionGroup.PROJECT,
        "프로젝트 작업",
        "GitHub 이슈를 만들고 라벨을 바꾸며, 풀 리퀘스트를 승인·병합할 수 있습니다. 다른 사람의 GitHub 댓글도 수정·삭제할 수 있습니다.",
    ),
    PROJECT_MANAGE(
        PermissionKeys.PROJECT_MANAGE,
        RolePermissionGroup.PROJECT,
        "프로젝트 관리",
        "프로젝트 이름, 설명, 상태, 공개 여부와 프로젝트 설정을 변경할 수 있습니다.",
    ),
    PROJECT_REORDER(
        PermissionKeys.PROJECT_REORDER,
        RolePermissionGroup.PROJECT,
        "프로젝트 순서 변경",
        "프로젝트 목록에서 프로젝트 순서를 바꿀 수 있습니다. 옮기는 프로젝트마다 이 권한이 있어야 합니다.",
    ),
    PROJECT_CHANNEL_ARCHIVE(
        PermissionKeys.PROJECT_CHANNEL_ARCHIVE,
        RolePermissionGroup.PROJECT,
        "프로젝트 채널 보관",
        "프로젝트에 속한 텍스트 채널을 보관하거나 복원할 수 있습니다. 보관된 채널은 읽기 전용이 되며, 볼 수 있는 채널만 보관할 수 있습니다. " +
            "프로젝트 역할로 프로젝트마다 허용하거나 거부할 수 있습니다.",
    ),
    PROJECT_BOARD_READ(
        PermissionKeys.PROJECT_BOARD_READ,
        RolePermissionGroup.PROJECT,
        "GitHub 보드 보기",
        "프로젝트에 연결된 GitHub Project 보드와 카드를 볼 수 있습니다. 프로젝트 보기 권한이 있어야 사용할 수 있습니다. " +
            "프로젝트 역할로 프로젝트마다 허용하거나 거부할 수 있습니다.",
    ),
    PROJECT_BOARD_WRITE(
        PermissionKeys.PROJECT_BOARD_WRITE,
        RolePermissionGroup.PROJECT,
        "GitHub 보드 수정",
        "프로젝트에 연결된 GitHub Project 보드에서 카드를 다른 컬럼으로 옮기거나 순서를 바꿀 수 있습니다. " +
            "GitHub 보드 보기 권한이 있어야 사용할 수 있습니다. 프로젝트 역할로 프로젝트마다 허용하거나 거부할 수 있습니다.",
    ),
    CALENDAR_EVENT_CREATE(
        PermissionKeys.CALENDAR_EVENT_CREATE,
        RolePermissionGroup.CALENDAR,
        "일정 만들기",
        "일정과 일정 카테고리를 만들 수 있습니다. 프로젝트 일정은 그 프로젝트를 볼 수 있어야 만들 수 있습니다.",
    ),
    CALENDAR_EVENT_UPDATE(
        PermissionKeys.CALENDAR_EVENT_UPDATE,
        RolePermissionGroup.CALENDAR,
        "다른 사람의 일정 수정",
        "다른 사람이 만든 일정과 일정 카테고리를 수정할 수 있습니다. 자기가 만든 일정은 이 권한 없이 수정할 수 있습니다.",
    ),
    CALENDAR_EVENT_DELETE(
        PermissionKeys.CALENDAR_EVENT_DELETE,
        RolePermissionGroup.CALENDAR,
        "다른 사람의 일정 삭제",
        "다른 사람이 만든 일정과 일정 카테고리를 삭제할 수 있습니다. 자기가 만든 일정은 이 권한 없이 삭제할 수 있습니다.",
    ),
    MANAGE_ROADMAPS(
        PermissionKeys.MANAGE_ROADMAPS,
        RolePermissionGroup.ROADMAP,
        "로드맵 관리",
        "팀과 프로젝트 로드맵을 만들고, 다른 사람이 만든 로드맵과 노드를 수정하거나 삭제할 수 있습니다. " +
            "로드맵 노드에 과제를 출제하고 다른 사람이 출제한 과제를 삭제할 수 있습니다. " +
            "자기가 만든 로드맵과 출제한 과제는 이 권한 없이 관리할 수 있습니다.",
    ),
    ;

    companion object {
        /**
         * 그룹별 권한 카탈로그를 만든다. [query]는 앞뒤 공백을 지우고 대소문자를 무시해 그룹 제목, 권한 제목·설명·키와 비교하며,
         * 그룹 제목이 일치하면 그 그룹의 권한을 모두 넣는다. [channelType]이 있으면 그 채널에 적용되는 권한만 남긴다.
         * 일치하는 권한이 없는 그룹은 뺀다.
         */
        fun catalog(query: String? = null, channelType: RolePermissionChannelType? = null): JsonArray {
            val term = query?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            return JsonArray(
                RolePermissionGroup.entries.mapNotNull { group ->
                    val groupMatched = term == null || group.title.lowercase(Locale.ROOT).contains(term)
                    val permissions = entries.filter { permission ->
                        permission.group == group &&
                            (groupMatched || (term != null && permission.matches(term))) &&
                            (channelType == null || channelType in permission.channelTypes)
                    }
                    if (permissions.isEmpty()) return@mapNotNull null
                    JsonObject()
                        .put("key", group.name)
                        .put("title", group.title)
                        .put(
                            "permissions",
                            JsonArray(
                                permissions.map { permission ->
                                    JsonObject()
                                        .put("key", permission.key)
                                        .put("title", permission.title)
                                        .put("description", permission.description)
                                        .put("channelTypes", JsonArray(permission.channelTypes.map { it.name }))
                                },
                            ),
                        )
                },
            )
        }

        private fun RolePermission.matches(term: String): Boolean =
            listOf(title, description, key).any { it.lowercase(Locale.ROOT).contains(term) }
    }
}
