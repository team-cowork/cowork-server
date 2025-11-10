package com.cowork.preference

import com.cowork.preference.domain.ResourceType
import com.cowork.preference.domain.SettingSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SettingSchemaTest {

    @Test
    fun `정의되지 않은 키는 걸러낸다`() {
        val filtered = SettingSchema.filter(ResourceType.ACCOUNT, mapOf("status" to "ONLINE", "unknown" to 1))

        assertEquals(mapOf("status" to "ONLINE"), filtered)
    }

    @Test
    fun `계정 상태는 허용된 값만 받는다`() {
        assertNull(SettingSchema.validate(ResourceType.ACCOUNT, mapOf("status" to "DO_NOT_DISTURB")))
        assertNotNull(SettingSchema.validate(ResourceType.ACCOUNT, mapOf("status" to "AWAY")))
    }

    @Test
    fun `상태 만료 시각은 ISO-8601 형식이어야 한다`() {
        assertNull(SettingSchema.validate(ResourceType.ACCOUNT, mapOf("status_expires_at" to "2025-12-01T09:00:00Z")))
        assertNotNull(SettingSchema.validate(ResourceType.ACCOUNT, mapOf("status_expires_at" to "2025-12-01 09:00")))
    }

    @Test
    fun `닉네임 형식을 강제하려면 예시가 필요하다`() {
        assertNotNull(SettingSchema.validate(ResourceType.TEAM, mapOf("nickname_format_enforced" to true)))
        assertNull(
            SettingSchema.validate(
                ResourceType.TEAM,
                mapOf("nickname_format_enforced" to true, "nickname_format_example" to "2학년 홍길동"),
            ),
        )
    }

    @Test
    fun `음성 채널 최대 인원은 1명 이상 50명 이하의 정수여야 한다`() {
        assertNull(SettingSchema.validate(ResourceType.VOICE_CHANNEL, mapOf("max_participants" to 10)))
        assertNotNull(SettingSchema.validate(ResourceType.VOICE_CHANNEL, mapOf("max_participants" to 0)))
        assertNotNull(SettingSchema.validate(ResourceType.VOICE_CHANNEL, mapOf("max_participants" to 2.5)))
    }

    @Test
    fun `null 값은 삭제 요청이므로 검증하지 않는다`() {
        assertNull(SettingSchema.validate(ResourceType.ACCOUNT, mapOf("status" to null)))
    }
}
