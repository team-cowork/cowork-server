package com.cowork.preference.domain

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SettingSchemaTest {

    @Test
    fun `방해 금지 예약은 허용한 키만 남긴다`() {
        val raw = JsonObject().put(
            "dnd_schedule",
            JsonObject().put("enabled", true).put("start", "22:00").put("end", "08:00").put("unknown", 1),
        )

        val filtered = SettingSchema.filter(ResourceType.ACCOUNT, raw)

        assertEquals(
            JsonObject().put("enabled", true).put("start", "22:00").put("end", "08:00"),
            filtered.getJsonObject("dnd_schedule"),
        )
    }

    @Test
    fun `유효한 계정 설정은 통과한다`() {
        val settings = JsonObject()
            .put("timezone", "Asia/Seoul")
            .put("notification_level", "MENTIONS")
            .put("message_display", "COMPACT")
            .put("enter_to_send", false)
            .put("dnd_schedule", JsonObject().put("enabled", true).put("start", "22:00").put("end", "08:00"))

        assertNull(SettingSchema.validate(ResourceType.ACCOUNT, settings))
    }

    @Test
    fun `잘못된 시간대나 방해 금지 시각은 거부한다`() {
        assertNotNull(SettingSchema.validate(ResourceType.ACCOUNT, JsonObject().put("timezone", "Mars/Base")))
        assertNotNull(
            SettingSchema.validate(
                ResourceType.ACCOUNT,
                JsonObject().put(
                    "dnd_schedule",
                    JsonObject().put("enabled", true).put("start", "25:00").put("end", "08:00"),
                ),
            ),
        )
    }

    @Test
    fun `슬로우 모드는 0초 이상 6시간 이하만 허용한다`() {
        assertNull(SettingSchema.validate(ResourceType.TEXT_CHANNEL, JsonObject().put("slowmode_seconds", 21_600)))
        assertNotNull(SettingSchema.validate(ResourceType.TEXT_CHANNEL, JsonObject().put("slowmode_seconds", 21_601)))
        assertNotNull(SettingSchema.validate(ResourceType.TEXT_CHANNEL, JsonObject().put("slowmode_seconds", -1)))
    }

    @Test
    fun `팀 기본 알림 수준은 정의된 값만 허용한다`() {
        assertNull(SettingSchema.validate(ResourceType.TEAM, JsonObject().put("default_notification_level", "ALL")))
        assertNotNull(SettingSchema.validate(ResourceType.TEAM, JsonObject().put("default_notification_level", "LOUD")))
    }
}
