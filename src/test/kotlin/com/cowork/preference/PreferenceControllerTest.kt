package com.cowork.preference

import com.cowork.preference.controller.PreferenceController
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.dto.SettingsResponse
import com.cowork.preference.service.PreferenceService
import com.cowork.support.ControllerTestSupport
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get

class PreferenceControllerTest : ControllerTestSupport() {

    private val preferenceService = mockk<PreferenceService>()
    private val mockMvc = mockMvc(PreferenceController(preferenceService))

    @Test
    fun `케밥 케이스 경로를 설정 유형으로 바꾼다`() {
        every { preferenceService.getSettings(ResourceType.VOICE_CHANNEL, 3, 1) } returns
            SettingsResponse(ResourceType.VOICE_CHANNEL, 3, mapOf("max_participants" to 8))

        mockMvc.get("/preferences/voice-channel/3").andExpect {
            status { isOk() }
            jsonPath("$.settings.max_participants") { value(8) }
        }
    }

    @Test
    fun `지원하지 않는 설정 유형은 404를 반환한다`() {
        mockMvc.get("/preferences/workspace/3").andExpect { status { isNotFound() } }
    }
}
