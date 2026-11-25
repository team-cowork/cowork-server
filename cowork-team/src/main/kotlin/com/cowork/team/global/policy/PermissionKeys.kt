package com.cowork.team.global.policy

import org.springframework.modulith.NamedInterface

@NamedInterface("policy", propagate = false)
object PermissionKeys {

    const val ADMINISTRATOR = "administrator"
    const val CREATE_TEAM = "create_team"
    const val DELETE_TEAM = "delete_team"
    const val TRANSFER_TEAM_OWNERSHIP = "transfer_team_ownership"

    const val MANAGE_TEAM = "manage_team"
    const val MANAGE_ROLES = "manage_roles"
    const val MANAGE_INVITES = "manage_invites"
    const val KICK_MEMBERS = "kick_members"
    const val MANAGE_CHANNELS = "manage_channels"
    const val MANAGE_PROJECTS = "manage_projects"
    const val MANAGE_GITHUB = "manage_github"
    const val MANAGE_APPLETS = "manage_applets"
    const val MANAGE_NICKNAMES = "manage_nicknames"
    const val MANAGE_NICKNAME_FORMAT = "manage_nickname_format"
    const val ARCHIVE_CHANNELS = "archive_channels"
    const val CALENDAR_EVENT_CREATE = "calendar_event_create"
    const val CALENDAR_EVENT_UPDATE = "calendar_event_update"
    const val CALENDAR_EVENT_DELETE = "calendar_event_delete"

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
    const val SLOWMODE_BYPASS = "slowmode_bypass"

    const val VOICE_CONNECT = "voice_connect"
    const val VOICE_SPEAK = "voice_speak"
    const val SCREEN_SHARE = "screen_share"
    const val LIVE_BROADCAST = "live_broadcast"
    const val VOICE_LOG_READ = "voice_log_read"

    val TEAM_SCOPED: Set<String> = linkedSetOf(
        ADMINISTRATOR,
        CREATE_TEAM,
        DELETE_TEAM,
        TRANSFER_TEAM_OWNERSHIP,
        MANAGE_TEAM,
        MANAGE_ROLES,
        MANAGE_INVITES,
        KICK_MEMBERS,
        MANAGE_CHANNELS,
        MANAGE_PROJECTS,
        MANAGE_GITHUB,
        MANAGE_APPLETS,
        MANAGE_NICKNAMES,
        MANAGE_NICKNAME_FORMAT,
        ARCHIVE_CHANNELS,
        CALENDAR_EVENT_CREATE,
        CALENDAR_EVENT_UPDATE,
        CALENDAR_EVENT_DELETE,
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
        SLOWMODE_BYPASS,
        VOICE_CONNECT,
        VOICE_SPEAK,
        SCREEN_SHARE,
        LIVE_BROADCAST,
        VOICE_LOG_READ,
    )

    const val PROJECT_READ = "project_read"
    const val PROJECT_WRITE = "project_write"
    const val PROJECT_MANAGE = "project_manage"
    const val PROJECT_REORDER = "project_reorder"
    const val PROJECT_CHANNEL_ARCHIVE = "project_channel_archive"
    const val PROJECT_BOARD_READ = "project_board_read"
    const val PROJECT_BOARD_WRITE = "project_board_write"

    val PROJECT_SCOPED: Set<String> = linkedSetOf(
        PROJECT_READ,
        PROJECT_WRITE,
        PROJECT_MANAGE,
        PROJECT_REORDER,
        PROJECT_CHANNEL_ARCHIVE,
        PROJECT_BOARD_READ,
        PROJECT_BOARD_WRITE,
    )

    val ROLE_SCOPED: Set<String> = buildSet {
        addAll(this@PermissionKeys.TEAM_SCOPED)
        addAll(this@PermissionKeys.CHANNEL_SCOPED)
        addAll(this@PermissionKeys.PROJECT_SCOPED)
    }
}
