package com.cowork.preference.domain.setting.model

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingSchemaTest {

    @Test
    fun `카탈로그는 각 리소스의 모든 설정에 제목 설명과 기본값을 제공한다`() {
        val catalog = SettingSchema.definitions().map { it as JsonObject }

        assertEquals(ResourceType.entries.map { it.name }, catalog.map { it.getString("resourceType") })
        catalog.forEach { group ->
            val type = ResourceType.valueOf(group.getString("resourceType"))
            val definitions = group.getJsonArray("settings").map { it as JsonObject }
            assertEquals(SettingSchema.validKeys(type), definitions.map { it.getString("key") }.toSet())
            definitions.forEach { definition ->
                assertTrue(definition.getString("title").isNotBlank())
                assertTrue(definition.getString("description").isNotBlank())
                assertTrue(definition.getString("type") in setOf("boolean", "integer", "string", "object"))
                assertTrue(definition.containsKey("defaultValue"))
                definition.getValue("defaultValue")?.let { value ->
                    assertNull(SettingSchema.validate(type, JsonObject().put(definition.getString("key"), value)))
                }
            }
        }
        val github = catalog.single { it.getString("resourceType") == "GITHUB_REPO" }.getJsonArray("settings")
        assertEquals(true, github.getJsonObject(0).getValue("defaultValue"))
        val account = catalog.single { it.getString("resourceType") == "ACCOUNT" }.getJsonArray("settings")
        val theme = account.map { it as JsonObject }.single { it.getString("key") == "theme" }
        assertNull(theme.getValue("defaultValue"))
        assertEquals(true, theme.getBoolean("nullable"))
        assertTrue(catalog.single { it.getString("resourceType") == "PROJECT" }.getJsonArray("settings").isEmpty)
    }

    @Test
    fun `정의된 기본값만 채우고 기존 값과 미설정 의미를 유지한다`() {
        val settings = JsonObject().put("label_auto_apply", false).put("unknown", true)

        assertEquals(
            JsonObject().put("label_auto_apply", false),
            SettingSchema.resolve(ResourceType.GITHUB_REPO, settings),
        )
        assertEquals(JsonObject().put("label_auto_apply", true), SettingSchema.resolve(ResourceType.GITHUB_REPO, null))
        assertEquals(
            JsonObject().put("label_auto_apply", true),
            SettingSchema.resolve(ResourceType.GITHUB_REPO, JsonObject().putNull("label_auto_apply")),
        )
        assertEquals(JsonObject(), SettingSchema.applyDefaults(ResourceType.VOICE_CHANNEL, null))
        assertEquals(JsonObject(), SettingSchema.filter(ResourceType.GITHUB_REPO, JsonObject()))
        assertEquals(JsonObject().put("label_auto_apply", false).put("unknown", true), settings)
    }

    @Test
    fun `계정 항목은 선언한 타입을 검사하고 기존 null 허용을 유지한다`() {
        for (key in listOf("status", "status_expires_at", "theme", "language", "time_format", "date_format")) {
            assertNotNull(SettingSchema.validate(ResourceType.ACCOUNT, JsonObject().put(key, 1)))
            assertNull(SettingSchema.validate(ResourceType.ACCOUNT, JsonObject().putNull(key)))
        }
        assertNull(SettingSchema.validate(ResourceType.ACCOUNT, JsonObject().put("marketing_email", false)))
        assertNotNull(SettingSchema.validate(ResourceType.ACCOUNT, JsonObject().put("marketing_email", "false")))
    }

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
    fun `상태 만료 시각은 UTC 연도 1에서 9999만 허용한다`() {
        fun validate(value: String) =
            SettingSchema.validate(ResourceType.ACCOUNT, JsonObject().put("status_expires_at", value))

        assertNull(validate("2026-04-19T12:00:00Z"))
        assertNull(validate("0001-01-01T00:00:00Z"))
        assertNull(validate("9999-12-31T23:59:59Z"))
        assertNotNull(validate("+300000-01-01T00:00:00Z"))
        assertNotNull(validate("+10000-01-01T00:00:00Z"))
        assertNotNull(validate("0000-12-31T23:59:59Z"))
        assertNotNull(validate("-0001-01-01T00:00:00Z"))
    }

    @Test
    fun `슬로우 모드는 0초 이상 6시간 이하만 허용한다`() {
        assertNull(SettingSchema.validate(ResourceType.TEXT_CHANNEL, JsonObject().put("slowmode_seconds", 21_600)))
        assertNotNull(SettingSchema.validate(ResourceType.TEXT_CHANNEL, JsonObject().put("slowmode_seconds", 21_601)))
        assertNotNull(SettingSchema.validate(ResourceType.TEXT_CHANNEL, JsonObject().put("slowmode_seconds", -1)))
    }

    @Test
    fun `음성 채널 최대 인원은 0에서 99, bitrate는 8000에서 128000 bps 정수만 허용한다`() {
        fun validate(key: String, value: Any) =
            SettingSchema.validate(ResourceType.VOICE_CHANNEL, JsonObject().put(key, value))

        assertNull(validate("max_participants", 0))
        assertNull(validate("max_participants", 99))
        assertNotNull(validate("max_participants", 100))
        assertNotNull(validate("max_participants", -1))
        assertNotNull(validate("max_participants", "10"))
        assertNull(validate("bitrate", 8_000))
        assertNull(validate("bitrate", 128_000))
        assertNotNull(validate("bitrate", 7_999))
        assertNotNull(validate("bitrate", 128_001))
    }

    @Test
    fun `팀 기본 알림 수준은 정의된 값만 허용한다`() {
        assertNull(SettingSchema.validate(ResourceType.TEAM, JsonObject().put("default_notification_level", "ALL")))
        assertNotNull(SettingSchema.validate(ResourceType.TEAM, JsonObject().put("default_notification_level", "LOUD")))
    }

    @Test
    fun `태그 스팸 차단은 boolean만 허용한다`() {
        assertNull(SettingSchema.validate(ResourceType.TEAM, JsonObject().put("tag_spam_block", true)))
        assertNotNull(SettingSchema.validate(ResourceType.TEAM, JsonObject().put("tag_spam_block", "true")))
    }
}
