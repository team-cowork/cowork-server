package com.cowork.preference.messaging

import com.cowork.preference.domain.AccountTeamRole
import com.cowork.preference.domain.TeamRoleDefinition
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class PreferenceEventsTest {

    private val occurredAt = Instant.parse("2026-08-26T01:02:03Z")

    @Test
    fun `channel notification event has stable key and payload`() {
        val event = PreferenceEvents.channelNotificationChanged(
            accountId = 11,
            channelId = 22,
            notification = false,
            occurredAt = occurredAt,
        )

        assertEquals("preference.channel-notification.changed", event.topic)
        assertEquals("11:22", event.key)
        assertEquals("UPSERT", event.payload.getString("eventType"))
        assertEquals(11L, event.payload.getLong("accountId"))
        assertEquals(22L, event.payload.getLong("channelId"))
        assertEquals(false, event.payload.getBoolean("notification"))
        assertEquals(occurredAt.toString(), event.payload.getString("occurredAt"))
    }

    @Test
    fun `team role upsert event contains complete read model`() {
        val role = TeamRoleDefinition(
            id = 7,
            teamId = 3,
            name = "Backend",
            colorHex = "#123ABC",
            priority = 10,
            mentionable = true,
            permissions = setOf("PROJECT_READ", "PROJECT_WRITE"),
            createdAt = null,
            updatedAt = null,
        )

        val event = PreferenceEvents.roleUpserted(role, occurredAt)

        assertEquals("preference.team-role.changed", event.topic)
        assertEquals("role:3:7", event.key)
        assertEquals("ROLE_UPSERTED", event.payload.getString("eventType"))
        assertEquals(7L, event.payload.getLong("roleId"))
        assertEquals(
            setOf("PROJECT_READ", "PROJECT_WRITE"),
            event.payload.getJsonArray("permissions").map {
                it.toString()
            }.toSet(),
        )
    }

    @Test
    fun `team role assignment events use assignment aggregate key`() {
        val assignment = AccountTeamRole(accountId = 19, teamId = 3, roleId = 7)

        val upserted = PreferenceEvents.assignmentUpserted(assignment, occurredAt)
        val deleted = PreferenceEvents.assignmentDeleted(assignment, occurredAt)

        assertEquals("assignment:3:19:7", upserted.key)
        assertEquals("ASSIGNMENT_UPSERTED", upserted.payload.getString("eventType"))
        assertEquals("assignment:3:19:7", deleted.key)
        assertEquals("ASSIGNMENT_DELETED", deleted.payload.getString("eventType"))
    }

    @Test
    fun `member assignment deletion identifies whole member aggregate`() {
        val event = PreferenceEvents.memberAssignmentsDeleted(teamId = 3, accountId = 19, occurredAt = occurredAt)

        assertEquals("member:3:19", event.key)
        assertEquals("MEMBER_ASSIGNMENTS_DELETED", event.payload.getString("eventType"))
        assertEquals(3L, event.payload.getLong("teamId"))
        assertEquals(19L, event.payload.getLong("accountId"))
    }

    @Test
    fun `status action event preserves previous status and one ordering timestamp`() {
        val event = PreferenceEvents.statusChanged(
            accountId = 19,
            previousStatus = "ONLINE",
            newStatus = "DO_NOT_DISTURB",
            reason = "MANUAL",
            occurredAt = occurredAt,
        )

        assertEquals("preference.status.changed", event.topic)
        assertEquals("19", event.key)
        assertEquals("ONLINE", event.payload.getString("previousStatus"))
        assertEquals("DO_NOT_DISTURB", event.payload.getString("newStatus"))
        assertEquals(occurredAt.toString(), event.payload.getString("timestamp"))
        assertEquals(occurredAt, event.occurredAt)
    }

    @Test
    fun `team settings action event carries changed and complete settings`() {
        val changed = JsonObject().put("nickname_format_enforced", true)
        val complete = changed.copy().put("nickname_format_example", "2-1 홍길동")
        val event = PreferenceEvents.teamSettingsChanged(3, changed, complete, occurredAt)

        assertEquals("preference.team.setting.changed", event.topic)
        assertEquals("3", event.key)
        assertEquals(changed, event.payload.getJsonObject("changedSettings"))
        assertEquals(complete, event.payload.getJsonObject("settings"))
        assertEquals(occurredAt.toString(), event.payload.getString("timestamp"))
    }

    @Test
    fun `snapshot completion emits one explicit marker for every state topic partition`() {
        val events = ProjectionSnapshotCompletionEvents.create(
            topicPartitions = mapOf(
                PreferenceEvents.CHANNEL_NOTIFICATION_TOPIC to setOf(0, 1),
                PreferenceEvents.TEAM_ROLE_TOPIC to setOf(0),
            ),
            snapshotId = "00000000-0000-0000-0000-000000000001",
            occurredAt = occurredAt,
        )

        assertEquals(3, events.size)
        assertEquals(
            setOf(
                "${PreferenceEvents.CHANNEL_NOTIFICATION_TOPIC}:0",
                "${PreferenceEvents.CHANNEL_NOTIFICATION_TOPIC}:1",
                "${PreferenceEvents.TEAM_ROLE_TOPIC}:0",
            ),
            events.map { "${it.topic}:${it.partition}" }.toSet(),
        )
        events.forEach { event ->
            assertEquals("${PreferenceEvents.SNAPSHOT_COMPLETED_KEY_PREFIX}${event.partition}", event.key)
            assertEquals(PreferenceEvents.SNAPSHOT_COMPLETED_EVENT_TYPE, event.payload.getString("eventType"))
            assertEquals(event.topic, event.payload.getString("topic"))
            assertEquals(event.partition, event.payload.getInteger("partition"))
            assertEquals("cowork-preference", event.payload.getString("source"))
        }
    }
}
