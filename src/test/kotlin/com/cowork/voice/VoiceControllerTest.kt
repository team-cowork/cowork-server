package com.cowork.voice

import com.cowork.support.ControllerTestSupport
import com.cowork.voice.controller.VoiceController
import com.cowork.voice.dto.JoinVoiceResponse
import com.cowork.voice.service.VoiceService
import com.cowork.voice.service.VoiceWebhookService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.post

class VoiceControllerTest : ControllerTestSupport() {

    private val voiceService = mockk<VoiceService>()
    private val voiceWebhookService = mockk<VoiceWebhookService>()
    private val mockMvc = mockMvc(VoiceController(voiceService, voiceWebhookService))

    @Test
    fun `음성 채널에 참여하면 LiveKit 접속 정보를 반환한다`() {
        every { voiceService.join(3, 1) } returns JoinVoiceResponse(100, "channel-3-room", "wss://livekit", "token")

        mockMvc.post("/voice/channels/3/join").andExpect {
            status { isOk() }
            jsonPath("$.roomName") { value("channel-3-room") }
            jsonPath("$.token") { value("token") }
        }
    }

    @Test
    fun `음성 채널에서 나가면 204를 반환한다`() {
        every { voiceService.leave(3, 1) } just runs

        mockMvc.post("/voice/channels/3/leave").andExpect { status { isNoContent() } }
    }

    @Test
    fun `LiveKit 웹훅은 원본 본문과 인증 헤더를 그대로 넘긴다`() {
        every { voiceWebhookService.handle("Bearer jwt", """{"event":"room_finished"}""") } just runs

        mockMvc.post("/voice/webhook") {
            contentType = org.springframework.http.MediaType.parseMediaType("application/webhook+json")
            header(HttpHeaders.AUTHORIZATION, "Bearer jwt")
            content = """{"event":"room_finished"}"""
        }.andExpect { status { isNoContent() } }

        verify { voiceWebhookService.handle("Bearer jwt", """{"event":"room_finished"}""") }
    }
}
