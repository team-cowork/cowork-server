package com.cowork.team.domain.teamRole.service.support

/** 역할 명령에 사용하는 권한 키 계약. 화면용 제목과 설명은 Preference의 역할 권한 카탈로그에서 제공한다. */
enum class TeamRolePermission(val key: String) {
    MANAGE_TEAM("manage_team"),
    MANAGE_ROLES("manage_roles"),
    MANAGE_GITHUB("manage_github"),
    MANAGE_NICKNAME_FORMAT("manage_nickname_format"),
    MANAGE_INVITES("manage_invites"),
    KICK_MEMBERS("kick_members"),
    MANAGE_NICKNAMES("manage_nicknames"),
    MANAGE_CHANNELS("manage_channels"),
    CHANNEL_MANAGE("channel_manage"),
    CHANNEL_REORDER("channel_reorder"),
    ARCHIVE_CHANNELS("archive_channels"),
    MESSAGE_READ("message_read"),
    MESSAGE_SEND("message_send"),
    THREAD_REPLY("thread_reply"),
    FILE_ATTACH("file_attach"),
    REACTION_ADD("reaction_add"),
    MESSAGE_PIN("message_pin"),
    MESSAGE_MANAGE("message_manage"),
    SLASH_COMMAND_USE("slash_command_use"),
    GITHUB_ISSUE_CREATE("github_issue_create"),
    SLOWMODE_BYPASS("slowmode_bypass"),
    VOICE_CONNECT("voice_connect"),
    VOICE_SPEAK("voice_speak"),
    SCREEN_SHARE("screen_share"),
    LIVE_BROADCAST("live_broadcast"),
    MANAGE_PROJECTS("manage_projects"),
    PROJECT_READ("project_read"),
    PROJECT_WRITE("project_write"),
    PROJECT_MANAGE("project_manage"),
    PROJECT_REORDER("project_reorder"),
    PROJECT_CHANNEL_ARCHIVE("project_channel_archive"),
    ;

    companion object {
        val KEYS: Set<String> = entries.mapTo(linkedSetOf()) { it.key }
    }
}
