package com.cowork.chat

import com.cowork.chat.domain.Message
import com.cowork.chat.domain.MessageType
import com.cowork.chat.dto.ChatSocketEvent
import com.cowork.chat.dto.MessageResponse
import com.cowork.chat.event.MessageCreatedEvent
import com.cowork.chat.event.MessageDeletedEvent
import com.cowork.chat.event.MessageUpdatedEvent
import com.cowork.chat.service.ChatEventBroadcaster
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.SimpMessagingTemplate

class ChatEventBroadcasterTest {

    private val messagingTemplate = mockk<SimpMessagingTemplate>(relaxed = true)
    private val broadcaster = ChatEventBroadcaster(messagingTemplate)

    private val response = MessageResponse.of(
        Message(channelId = 10, senderId = 1, type = MessageType.TEXT, content = "hello", id = 5),
        null,
        emptyList(),
    )

    @Test
    fun `생성된 메시지는 채널 토픽으로 전송한다`() {
        broadcaster.onCreated(MessageCreatedEvent(1, response, emptySet()))

        verify { messagingTemplate.convertAndSend("/topic/channels/10", ChatSocketEvent("MESSAGE_CREATED", response)) }
    }

    @Test
    fun `수정된 메시지는 수정 이벤트로 전송한다`() {
        broadcaster.onUpdated(MessageUpdatedEvent(response))

        verify { messagingTemplate.convertAndSend("/topic/channels/10", ChatSocketEvent("MESSAGE_UPDATED", response)) }
    }

    @Test
    fun `삭제된 메시지는 ID만 전송한다`() {
        broadcaster.onDeleted(MessageDeletedEvent(10, 5))

        verify {
            messagingTemplate.convertAndSend("/topic/channels/10", ChatSocketEvent("MESSAGE_DELETED", mapOf("messageId" to 5L)))
        }
    }
}
