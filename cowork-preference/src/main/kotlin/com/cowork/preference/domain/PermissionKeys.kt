package com.cowork.preference.domain

object PermissionKeys {

    const val MANAGE_TEAM = "manage_team"
    const val MANAGE_ROLES = "manage_roles"
    const val MANAGE_INVITES = "manage_invites"
    const val KICK_MEMBERS = "kick_members"
    const val MANAGE_CHANNELS = "manage_channels"
    const val MANAGE_PROJECTS = "manage_projects"
    const val MANAGE_GITHUB = "manage_github"

    const val MESSAGE_READ = "message_read"
    const val MESSAGE_SEND = "message_send"
    const val THREAD_REPLY = "thread_reply"
    const val FILE_ATTACH = "file_attach"
    const val REACTION_ADD = "reaction_add"
    const val MESSAGE_PIN = "message_pin"
    const val MESSAGE_MANAGE = "message_manage"
    const val SLASH_COMMAND_USE = "slash_command_use"
    const val GITHUB_ISSUE_CREATE = "github_issue_create"
    const val CHANNEL_MANAGE = "channel_manage"
    const val CHANNEL_REORDER = "channel_reorder"
    // TODO: 임시 호환 키이며 멤버 관리 기능은 없다.
    // 저장된 역할·정책과 보관 이벤트에서 이 키를 제거하고 생산자·소비자를 함께 전환한 뒤 삭제한다.
    const val CHANNEL_MEMBER_MANAGE = "channel_member_manage"

    const val VOICE_CONNECT = "voice_connect"
    const val VOICE_SPEAK = "voice_speak"
    const val SCREEN_SHARE = "screen_share"
    const val LIVE_BROADCAST = "live_broadcast"

    val TEAM_SCOPED: Set<String> = linkedSetOf(
        MANAGE_TEAM,
        MANAGE_ROLES,
        MANAGE_INVITES,
        KICK_MEMBERS,
        MANAGE_CHANNELS,
        MANAGE_PROJECTS,
        MANAGE_GITHUB,
    )

    val CHANNEL_SCOPED: Set<String> = linkedSetOf(
        MESSAGE_READ,
        MESSAGE_SEND,
        THREAD_REPLY,
        FILE_ATTACH,
        REACTION_ADD,
        MESSAGE_PIN,
        MESSAGE_MANAGE,
        SLASH_COMMAND_USE,
        GITHUB_ISSUE_CREATE,
        CHANNEL_MANAGE,
        CHANNEL_REORDER,
        CHANNEL_MEMBER_MANAGE,
        VOICE_CONNECT,
        VOICE_SPEAK,
        SCREEN_SHARE,
        LIVE_BROADCAST,
    )

    val ROLE_SCOPED: Set<String> = TEAM_SCOPED + CHANNEL_SCOPED
}
