package com.cowork.preference.messaging

import com.cowork.preference.domain.AccountTeamRole
import com.cowork.preference.domain.TeamRoleDefinition
import io.vertx.core.json.JsonArray
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
    const val TEAM_ROLE_TOPIC = "preference.team-role.changed"
    const val STATUS_CHANGED_TOPIC = "preference.status.changed"
    const val TEAM_SETTING_CHANGED_TOPIC = "preference.team.setting.changed"
    val OUTBOX_TOPICS = setOf(
        CHANNEL_NOTIFICATION_TOPIC,
        TEAM_ROLE_TOPIC,
        STATUS_CHANGED_TOPIC,
        TEAM_SETTING_CHANGED_TOPIC,
    )

    const val SNAPSHOT_COMPLETED_EVENT_TYPE = "PROJECTION_SNAPSHOT_COMPLETED"
    const val SNAPSHOT_COMPLETED_KEY_PREFIX = "__cowork_projection_snapshot_complete__:"

    fun snapshotCompleted(topic: String, partition: Int, snapshotId: String, occurredAt: Instant): PreferenceEvent {
        require(topic == CHANNEL_NOTIFICATION_TOPIC || topic == TEAM_ROLE_TOPIC) {
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

    fun channelNotificationChanged(
        accountId: Long,
        channelId: Long,
        notification: Boolean,
        occurredAt: Instant,
    ): PreferenceEvent = PreferenceEvent(
        topic = CHANNEL_NOTIFICATION_TOPIC,
        key = "$accountId:$channelId",
        payload = basePayload("UPSERT", occurredAt)
            .put("accountId", accountId)
            .put("channelId", channelId)
            .put("notification", notification),
        occurredAt = occurredAt,
    )

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
            .put("permissions", JsonArray(role.permissions.sorted())),
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

    fun teamSettingsChanged(
        teamId: Long,
        changedSettings: JsonObject,
        settings: JsonObject,
        occurredAt: Instant = Instant.now(),
    ): PreferenceEvent = PreferenceEvent(
        topic = TEAM_SETTING_CHANGED_TOPIC,
        key = teamId.toString(),
        payload = JsonObject()
            .put("teamId", teamId)
            .put("changedSettings", changedSettings)
            .put("settings", settings)
            .put("timestamp", occurredAt.toString()),
        occurredAt = occurredAt,
    )

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
