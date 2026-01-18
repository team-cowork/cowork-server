package com.cowork.voice

import com.cowork.voice.livekit.LiveKitWebhookVerifier
import com.cowork.voice.service.VoiceService
import com.cowork.voice.service.VoiceWebhookService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class VoiceWebhookServiceTest {

    private val verifier = mockk<LiveKitWebhookVerifier>()
    private val voiceService = mockk<VoiceService>(relaxed = true)
    private val service = VoiceWebhookService(verifier, voiceService, jacksonObjectMapper())

    @BeforeEach
    fun allow() {
        every { verifier.verify(any(), any()) } just runs
    }

    @Test
    fun `참여 이벤트는 참여자 입장으로 처리한다`() {
        service.handle("Bearer t", """{"event":"participant_joined","room":{"name":"r1"},"participant":{"identity":"7"}}""")

        verify { voiceService.handleParticipantJoined("r1", "7") }
    }

    @Test
    fun `방 종료 이벤트는 세션 종료로 처리한다`() {
        service.handle("Bearer t", """{"event":"room_finished","room":{"name":"r1","sid":"RM_1"}}""")

        verify { voiceService.handleRoomFinished("r1") }
    }

    @Test
    fun `방 정보가 없는 이벤트는 무시한다`() {
        service.handle("Bearer t", """{"event":"egress_started"}""")

        verify(exactly = 0) { voiceService.handleRoomFinished(any()) }
    }
}
