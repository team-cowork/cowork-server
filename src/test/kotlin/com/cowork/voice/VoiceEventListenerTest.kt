package com.cowork.voice

import com.cowork.channel.event.ChannelDeletedEvent
import com.cowork.voice.dto.VoiceSocketEvent
import com.cowork.voice.service.VoiceEventListener
import com.cowork.voice.service.VoiceService
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.SimpMessagingTemplate

class VoiceEventListenerTest {

    private val voiceService = mockk<VoiceService>(relaxed = true)
    private val messagingTemplate = mockk<SimpMessagingTemplate>(relaxed = true)
    private val listener = VoiceEventListener(voiceService, messagingTemplate)

    @Test
    fun `채널이 삭제되면 진행 중인 음성 세션을 끝낸다`() {
        listener.onChannelDeleted(ChannelDeletedEvent(3, 1))

        verify { voiceService.endChannelSessions(3) }
    }

    @Test
    fun `참여자 변경은 채널 음성 토픽으로 전송한다`() {
        val event = VoiceSocketEvent("PARTICIPANT_JOINED", 3, 7)

        listener.onVoiceEvent(event)

        verify { messagingTemplate.convertAndSend("/topic/channels/3/voice", event) }
    }
}
