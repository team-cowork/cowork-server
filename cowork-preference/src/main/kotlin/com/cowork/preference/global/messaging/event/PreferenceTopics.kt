package com.cowork.preference.global.messaging.event

object PreferenceTopics {
    const val CHANNEL_NOTIFICATION_TOPIC = "preference.channel-notification.changed"
    const val PROJECT_ACCESS_STATE_TOPIC = "preference.project-access.state.v1"
    const val TEAM_ROLE_TOPIC = "preference.team-role.state.v2"
    const val TEAM_ROLE_COMMAND_TOPIC = "preference.team-role.command.v2"
    const val TEAM_ROLE_COMMAND_RESULT_TOPIC = "preference.team-role.result.v2"
    const val GITHUB_REPO_SETTING_COMMAND_TOPIC = "preference.github-repo.setting.command"
    const val GITHUB_REPO_SETTING_STATE_TOPIC = "preference.github-repo.setting.state"
    const val GITHUB_REPO_SETTING_RESULT_TOPIC = "preference.github-repo.setting.result"
    const val CHANNEL_ROLE_POLICY_COMMAND_TOPIC = "preference.channel-role-policy.command.v2"
    const val CHANNEL_ROLE_POLICY_STATE_TOPIC = "preference.channel-role-policy.state.v2"
    const val CHANNEL_ROLE_POLICY_COMMAND_RESULT_TOPIC = "preference.channel-role-policy.result.v2"
    const val STATUS_CHANGED_TOPIC = "preference.status.changed"
    const val ACCOUNT_SETTING_STATE_TOPIC = "preference.account-setting.state.v1"
    const val TEAM_SETTING_STATE_TOPIC = "preference.team-setting.state.v1"
    const val TEXT_CHANNEL_SETTING_STATE_TOPIC = "preference.text-channel-setting.state.v1"
    const val WEBHOOK_STATE_TOPIC = "preference.webhook.state.v2"
    const val VOICE_CHANNEL_SETTING_STATE_TOPIC = "preference.voice-channel-setting.state.v1"
    val OUTBOX_TOPICS = setOf(
        PROJECT_ACCESS_STATE_TOPIC,
        CHANNEL_NOTIFICATION_TOPIC,
        TEAM_ROLE_TOPIC,
        TEAM_ROLE_COMMAND_RESULT_TOPIC,
        GITHUB_REPO_SETTING_STATE_TOPIC,
        GITHUB_REPO_SETTING_RESULT_TOPIC,
        CHANNEL_ROLE_POLICY_STATE_TOPIC,
        CHANNEL_ROLE_POLICY_COMMAND_RESULT_TOPIC,
        STATUS_CHANGED_TOPIC,
        ACCOUNT_SETTING_STATE_TOPIC,
        TEAM_SETTING_STATE_TOPIC,
        TEXT_CHANNEL_SETTING_STATE_TOPIC,
        WEBHOOK_STATE_TOPIC,
        VOICE_CHANNEL_SETTING_STATE_TOPIC,
    )
}
