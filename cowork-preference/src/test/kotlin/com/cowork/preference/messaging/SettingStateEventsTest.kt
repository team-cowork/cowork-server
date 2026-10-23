package com.cowork.preference.messaging

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class SettingStateEventsTest {

    private val occurredAt = Instant.parse("2026-10-23T01:02:03Z")

    @Test
    fun `계정 설정 state는 알림 판단에 쓰는 값만 계정 키로 싣는다`() {
        val dndSchedule = JsonObject().put("enabled", true).put("start", "22:00").put("end", "08:00")
        val settings = JsonObject()
            .put("notification_level", "MENTIONS")
            .put("status", "DO_NOT_DISTURB")
            .put("status_expires_at", "2026-10-23T18:00:00+09:00")
            .put("timezone", "Asia/Seoul")
            .put("dnd_schedule", dndSchedule)
            .put("theme", "BLACK")

        val event = PreferenceEvents.accountSettingState(7L, settings, occurredAt, snapshot = false)

        assertEquals(PreferenceEvents.ACCOUNT_SETTING_STATE_TOPIC, event.topic)
        assertEquals("7", event.key)
        assertEquals(occurredAt, event.occurredAt)
        assertEquals(
            JsonObject()
                .put("eventType", "UPSERT")
                .put("occurredAt", occurredAt.toString())
                .put("schemaVersion", 1)
                .put("accountId", 7L)
                .put("notificationLevel", "MENTIONS")
                .put("status", "DO_NOT_DISTURB")
                .put("statusExpiresAt", "2026-10-23T09:00:00Z")
                .put("timezone", "Asia/Seoul")
                .put("dndSchedule", dndSchedule)
                .put("snapshot", false),
            event.payload,
        )
    }

    @Test
    fun `검증을 통과하지 않는 과거 계정 설정 값은 null로 보낸다`() {
        val settings = JsonObject()
            .put("notification_level", "LOUD")
            .put("status", "BUSY")
            .put("timezone", "Mars/Base")
            .put("dnd_schedule", JsonObject().put("enabled", true).put("start", "25:00").put("end", "08:00"))

        val payload = PreferenceEvents.accountSettingState(7L, settings, occurredAt, snapshot = true).payload

        assertNull(payload.getValue("notificationLevel"))
        assertNull(payload.getValue("status"))
        assertNull(payload.getValue("statusExpiresAt"))
        assertNull(payload.getValue("timezone"))
        assertNull(payload.getValue("dndSchedule"))
    }

    @Test
    fun `팀 설정 state는 기본 알림 수준을 팀 키로 싣는다`() {
        val event = PreferenceEvents.teamSettingState(
            3L,
            JsonObject().put("default_notification_level", "NONE").put("tag_spam_block", true),
            occurredAt,
            snapshot = true,
        )

        assertEquals(PreferenceEvents.TEAM_SETTING_STATE_TOPIC, event.topic)
        assertEquals("3", event.key)
        assertEquals("NONE", event.payload.getString("defaultNotificationLevel"))
        assertEquals(true, event.payload.getBoolean("snapshot"))
        assertNull(event.payload.getValue("tag_spam_block"))
    }
}
