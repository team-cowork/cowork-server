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
        assertEquals(true, event.payload.getBoolean("tagSpamBlock"))
        assertNull(event.payload.getValue("tag_spam_block"))
    }

    @Test
    fun `팀 설정 state는 예시가 있을 때만 닉네임 형식 안내를 켠다`() {
        val enabled = PreferenceEvents.teamSettingState(
            3L,
            JsonObject().put("nickname_format_enforced", true).put("nickname_format_example", "홍길동 / 프론트엔드"),
            occurredAt,
            snapshot = false,
        )
        val missingExample = PreferenceEvents.teamSettingState(
            4L,
            JsonObject().put("nickname_format_enforced", true),
            occurredAt,
            snapshot = false,
        )

        assertEquals(true, enabled.payload.getBoolean("nicknameFormatEnforced"))
        assertEquals("홍길동 / 프론트엔드", enabled.payload.getString("nicknameFormatExample"))
        assertEquals(false, missingExample.payload.getBoolean("nicknameFormatEnforced"))
        assertNull(missingExample.payload.getValue("nicknameFormatExample"))
    }

    @Test
    fun `텍스트 채널 설정 state는 슬로우 모드 간격을 채널 키로 싣고 없으면 0으로 보낸다`() {
        val enabled = PreferenceEvents.textChannelSettingState(
            11L,
            JsonObject().put("slowmode_seconds", 30),
            occurredAt,
            snapshot = false,
        )
        val unset = PreferenceEvents.textChannelSettingState(12L, JsonObject(), occurredAt, snapshot = true)

        assertEquals(PreferenceEvents.TEXT_CHANNEL_SETTING_STATE_TOPIC, enabled.topic)
        assertEquals("11", enabled.key)
        assertEquals(30, enabled.payload.getInteger("slowmodeSeconds"))
        assertEquals(0, unset.payload.getInteger("slowmodeSeconds"))
    }

    @Test
    fun `음성 채널 설정 state는 범위를 벗어난 과거 값을 미설정으로 보낸다`() {
        val configured = PreferenceEvents.voiceChannelSettingState(
            21L,
            JsonObject().put("max_participants", 10).put("bitrate", 64_000),
            occurredAt,
            snapshot = false,
        )
        val invalid = PreferenceEvents.voiceChannelSettingState(
            22L,
            JsonObject().put("max_participants", 500).put("bitrate", 1),
            occurredAt,
            snapshot = true,
        )

        assertEquals(PreferenceEvents.VOICE_CHANNEL_SETTING_STATE_TOPIC, configured.topic)
        assertEquals("21", configured.key)
        assertEquals(10, configured.payload.getInteger("maxParticipants"))
        assertEquals(64_000, configured.payload.getInteger("bitrate"))
        assertEquals(0, invalid.payload.getInteger("maxParticipants"))
        assertNull(invalid.payload.getValue("bitrate"))
    }
}
