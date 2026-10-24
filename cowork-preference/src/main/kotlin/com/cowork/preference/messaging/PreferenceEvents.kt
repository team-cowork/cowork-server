package com.cowork.preference.messaging

import com.cowork.preference.domain.AccountTeamRole
import com.cowork.preference.domain.ChannelNotificationPreference
import com.cowork.preference.domain.ChannelRolePolicyPermissions
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.domain.SettingSchema
import com.cowork.preference.domain.TeamRoleDefinition
import com.cowork.preference.domain.TeamRolePermissions
import io.vertx.core.json.JsonObject
import java.time.Instant

data class PreferenceEvent(
    val topic: String,
    val key: String,
    val payload: JsonObject,
    val occurredAt: Instant,
    val partition: Int? = null,
)

object PreferenceEvents {
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
    const val SETTING_STATE_SCHEMA_VERSION = 1
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
    )

    const val SNAPSHOT_COMPLETED_EVENT_TYPE = "PROJECTION_SNAPSHOT_COMPLETED"
    const val SNAPSHOT_COMPLETED_KEY_PREFIX = "__cowork_projection_snapshot_complete__:"

    fun snapshotCompleted(topic: String, partition: Int, snapshotId: String, occurredAt: Instant): PreferenceEvent {
        require(
            topic == PROJECT_ACCESS_STATE_TOPIC ||
                topic == CHANNEL_NOTIFICATION_TOPIC ||
                topic == TEAM_ROLE_TOPIC ||
                topic == GITHUB_REPO_SETTING_STATE_TOPIC ||
                topic == CHANNEL_ROLE_POLICY_STATE_TOPIC ||
                topic == ACCOUNT_SETTING_STATE_TOPIC ||
                topic == TEAM_SETTING_STATE_TOPIC ||
                topic == TEXT_CHANNEL_SETTING_STATE_TOPIC,
        ) {
            "unsupported projection snapshot topic '$topic'"
        }
        require(partition >= 0) { "partition must not be negative" }
        return PreferenceEvent(
            topic = topic,
            key = "$SNAPSHOT_COMPLETED_KEY_PREFIX$partition",
            payload = JsonObject()
                .put("eventType", SNAPSHOT_COMPLETED_EVENT_TYPE)
                .put("topic", topic)
                .put("partition", partition)
                .put("snapshotId", snapshotId)
                .put("occurredAt", occurredAt.toString())
                .put("source", "cowork-preference"),
            occurredAt = occurredAt,
            partition = partition,
        )
    }

    fun channelNotificationChanged(preference: ChannelNotificationPreference): PreferenceEvent {
        val occurredAt = preference.stateOccurredAt.toInstant()
        return PreferenceEvent(
            topic = CHANNEL_NOTIFICATION_TOPIC,
            key = "${preference.accountId}:${preference.channelId}",
            payload = basePayload("UPSERT", occurredAt)
                .put("accountId", preference.accountId)
                .put("channelId", preference.channelId)
                .put("notification", preference.notification)
                .put("notificationLevel", preference.notificationLevelName)
                .put("mutedUntil", preference.mutedUntil?.toString()),
            occurredAt = occurredAt,
        )
    }

    fun roleUpserted(role: TeamRoleDefinition, occurredAt: Instant): PreferenceEvent = PreferenceEvent(
        topic = TEAM_ROLE_TOPIC,
        key = "role:${role.teamId}:${role.id}",
        payload = basePayload("ROLE_UPSERTED", occurredAt)
            .put("teamId", role.teamId)
            .put("roleId", role.id)
            .put("name", role.name)
            .put("colorHex", role.colorHex)
            .put("priority", role.priority)
            .put("mentionable", role.mentionable)
            .put("permissions", TeamRolePermissions.toJson(role.permissions)),
        occurredAt = occurredAt,
    )

    fun roleDeleted(teamId: Long, roleId: Long, occurredAt: Instant): PreferenceEvent = PreferenceEvent(
        topic = TEAM_ROLE_TOPIC,
        key = "role:$teamId:$roleId",
        payload = basePayload("ROLE_DELETED", occurredAt)
            .put("teamId", teamId)
            .put("roleId", roleId),
        occurredAt = occurredAt,
    )

    fun assignmentUpserted(assignment: AccountTeamRole, occurredAt: Instant): PreferenceEvent =
        assignmentChanged("ASSIGNMENT_UPSERTED", assignment, occurredAt)

    fun assignmentDeleted(assignment: AccountTeamRole, occurredAt: Instant): PreferenceEvent =
        assignmentChanged("ASSIGNMENT_DELETED", assignment, occurredAt)

    fun memberAssignmentsDeleted(teamId: Long, accountId: Long, occurredAt: Instant): PreferenceEvent = PreferenceEvent(
        topic = TEAM_ROLE_TOPIC,
        key = "member:$teamId:$accountId",
        payload = basePayload("MEMBER_ASSIGNMENTS_DELETED", occurredAt)
            .put("teamId", teamId)
            .put("accountId", accountId),
        occurredAt = occurredAt,
    )

    fun teamRoleCommandResult(operationId: String, result: JsonObject, occurredAt: Instant): PreferenceEvent =
        PreferenceEvent(
            topic = TEAM_ROLE_COMMAND_RESULT_TOPIC,
            key = operationId,
            payload = result,
            occurredAt = occurredAt,
        )

    fun githubRepoSettingState(
        repoId: Long,
        settings: JsonObject?,
        occurredAt: Instant,
        snapshot: Boolean,
    ): PreferenceEvent {
        val deleted = settings == null
        val normalized = settings?.let {
            JsonObject().put("label_auto_apply", it.getBoolean("label_auto_apply", true))
        }
        return PreferenceEvent(
            topic = GITHUB_REPO_SETTING_STATE_TOPIC,
            key = repoId.toString(),
            payload = basePayload(if (deleted) "DELETE" else "UPSERT", occurredAt)
                .put("schemaVersion", 1)
                .put("repoId", repoId)
                .put("settings", normalized)
                .put("snapshot", snapshot),
            occurredAt = occurredAt,
        )
    }

    fun githubRepoSettingResult(operationId: String, result: JsonObject, occurredAt: Instant): PreferenceEvent =
        PreferenceEvent(
            topic = GITHUB_REPO_SETTING_RESULT_TOPIC,
            key = operationId,
            payload = result,
            occurredAt = occurredAt,
        )

    fun channelRolePolicyState(
        teamId: Long,
        channelId: Long,
        roleId: Long,
        permissions: JsonObject?,
        occurredAt: Instant,
        snapshot: Boolean,
    ): PreferenceEvent {
        val canonicalPermissions = permissions?.let(ChannelRolePolicyPermissions::canonicalize)
        return PreferenceEvent(
            topic = CHANNEL_ROLE_POLICY_STATE_TOPIC,
            key = ChannelRolePolicyCommandParser.policyKey(teamId, channelId, roleId),
            payload = basePayload(if (canonicalPermissions == null) "DELETE" else "UPSERT", occurredAt)
                .put("schemaVersion", ChannelRolePolicyCommandParser.SCHEMA_VERSION)
                .put("teamId", teamId)
                .put("channelId", channelId)
                .put("roleId", roleId)
                .put("permissions", canonicalPermissions?.copy())
                .put("snapshot", snapshot),
            occurredAt = occurredAt,
        )
    }

    fun channelRolePolicyCommandResult(operationId: String, result: JsonObject, occurredAt: Instant): PreferenceEvent =
        PreferenceEvent(
            topic = CHANNEL_ROLE_POLICY_COMMAND_RESULT_TOPIC,
            key = operationId,
            payload = result,
            occurredAt = occurredAt,
        )

    fun statusChanged(
        accountId: Long,
        previousStatus: String?,
        newStatus: String?,
        reason: String,
        occurredAt: Instant = Instant.now(),
    ): PreferenceEvent = PreferenceEvent(
        topic = STATUS_CHANGED_TOPIC,
        key = accountId.toString(),
        payload = JsonObject()
            .put("accountId", accountId)
            .put("previousStatus", previousStatus)
            .put("newStatus", newStatus)
            .put("reason", reason)
            .put("timestamp", occurredAt.toString()),
        occurredAt = occurredAt,
    )

    /** 알림 발송 판단에 쓰는 계정 설정만 싣는다. 검증을 통과하지 않는 과거 값은 소비자가 격리하지 않도록 null로 보낸다. */
    fun accountSettingState(
        accountId: Long,
        settings: JsonObject,
        occurredAt: Instant,
        snapshot: Boolean,
    ): PreferenceEvent = PreferenceEvent(
        topic = ACCOUNT_SETTING_STATE_TOPIC,
        key = accountId.toString(),
        payload = basePayload("UPSERT", occurredAt)
            .put("schemaVersion", SETTING_STATE_SCHEMA_VERSION)
            .put("accountId", accountId)
            .put("notificationLevel", validSetting(ResourceType.ACCOUNT, settings, "notification_level"))
            .put("status", validSetting(ResourceType.ACCOUNT, settings, "status"))
            .put(
                "statusExpiresAt",
                (validSetting(ResourceType.ACCOUNT, settings, "status_expires_at") as? String)
                    ?.let { Instant.parse(it).toString() },
            )
            .put("timezone", validSetting(ResourceType.ACCOUNT, settings, "timezone"))
            .put(
                "dndSchedule",
                (validSetting(ResourceType.ACCOUNT, settings, "dnd_schedule") as? JsonObject)?.copy(),
            )
            .put("snapshot", snapshot),
        occurredAt = occurredAt,
    )

    fun teamSettingState(
        teamId: Long,
        settings: JsonObject,
        occurredAt: Instant,
        snapshot: Boolean,
    ): PreferenceEvent = PreferenceEvent(
        topic = TEAM_SETTING_STATE_TOPIC,
        key = teamId.toString(),
        payload = basePayload("UPSERT", occurredAt)
            .put("schemaVersion", SETTING_STATE_SCHEMA_VERSION)
            .put("teamId", teamId)
            .put(
                "defaultNotificationLevel",
                validSetting(ResourceType.TEAM, settings, "default_notification_level"),
            )
            .put("snapshot", snapshot),
        occurredAt = occurredAt,
    )

    fun textChannelSettingState(
        channelId: Long,
        settings: JsonObject,
        occurredAt: Instant,
        snapshot: Boolean,
    ): PreferenceEvent = PreferenceEvent(
        topic = TEXT_CHANNEL_SETTING_STATE_TOPIC,
        key = channelId.toString(),
        payload = basePayload("UPSERT", occurredAt)
            .put("schemaVersion", SETTING_STATE_SCHEMA_VERSION)
            .put("channelId", channelId)
            .put(
                "slowmodeSeconds",
                validSetting(ResourceType.TEXT_CHANNEL, settings, "slowmode_seconds") as? Int ?: 0,
            )
            .put("snapshot", snapshot),
        occurredAt = occurredAt,
    )

    private fun validSetting(type: ResourceType, settings: JsonObject, key: String): Any? {
        val value = settings.getValue(key) ?: return null
        return value.takeIf { SettingSchema.validate(type, JsonObject().put(key, value)) == null }
    }

    private fun assignmentChanged(
        eventType: String,
        assignment: AccountTeamRole,
        occurredAt: Instant,
    ): PreferenceEvent = PreferenceEvent(
        topic = TEAM_ROLE_TOPIC,
        key = "assignment:${assignment.teamId}:${assignment.accountId}:${assignment.roleId}",
        payload = basePayload(eventType, occurredAt)
            .put("teamId", assignment.teamId)
            .put("accountId", assignment.accountId)
            .put("roleId", assignment.roleId),
        occurredAt = occurredAt,
    )

    private fun basePayload(eventType: String, occurredAt: Instant): JsonObject = JsonObject()
        .put("eventType", eventType)
        .put("occurredAt", occurredAt.toString())
}
