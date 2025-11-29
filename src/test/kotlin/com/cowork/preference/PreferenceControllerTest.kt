package com.cowork.preference

import com.cowork.preference.controller.PreferenceController
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.dto.ChannelNotificationResponse
import com.cowork.preference.dto.SettingsResponse
import com.cowork.preference.service.PreferenceService
import com.cowork.support.ControllerTestSupport
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put

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

    @Test
    fun `설정 변경 요청의 null 값은 그대로 넘긴다`() {
        every { preferenceService.updateSettings(ResourceType.ACCOUNT, 1, 1, mapOf("theme" to null)) } returns
            SettingsResponse(ResourceType.ACCOUNT, 1, emptyMap())

        mockMvc.put("/preferences/account/1") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"theme":null}"""
        }.andExpect { status { isOk() } }

        verify { preferenceService.updateSettings(ResourceType.ACCOUNT, 1, 1, mapOf("theme" to null)) }
    }

    @Test
    fun `채널 알림을 끈다`() {
        every { preferenceService.updateChannelNotification(1, 10, false) } returns ChannelNotificationResponse(10, false)

        mockMvc.put("/preferences/account/me/channels/10/notification") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"notification":false}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.notification") { value(false) }
        }
    }
}
