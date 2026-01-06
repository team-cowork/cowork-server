package com.cowork.voice.service

import com.cowork.channel.event.ChannelDeletedEvent
import com.cowork.voice.dto.VoiceSocketEvent
import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

@Component
class VoiceEventListener(
    private val voiceService: VoiceService,
    private val messagingTemplate: SimpMessagingTemplate,
) {

    @EventListener
    fun onChannelDeleted(event: ChannelDeletedEvent) {
        voiceService.endChannelSessions(event.channelId)
    }

    @TransactionalEventListener
    fun onVoiceEvent(event: VoiceSocketEvent) {
        messagingTemplate.convertAndSend("/topic/channels/${event.channelId}/voice", event)
    }
}
