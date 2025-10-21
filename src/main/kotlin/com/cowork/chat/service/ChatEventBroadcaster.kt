package com.cowork.chat.service

import com.cowork.chat.dto.ChatSocketEvent
import com.cowork.chat.event.MessageCreatedEvent
import com.cowork.chat.event.MessageDeletedEvent
import com.cowork.chat.event.MessageUpdatedEvent
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

@Component
class ChatEventBroadcaster(
    private val messagingTemplate: SimpMessagingTemplate,
) {

    @TransactionalEventListener
    fun onCreated(event: MessageCreatedEvent) {
        send(event.message.channelId, ChatSocketEvent("MESSAGE_CREATED", event.message))
    }

    @TransactionalEventListener
    fun onUpdated(event: MessageUpdatedEvent) {
        send(event.message.channelId, ChatSocketEvent("MESSAGE_UPDATED", event.message))
    }

    @TransactionalEventListener
    fun onDeleted(event: MessageDeletedEvent) {
        send(event.channelId, ChatSocketEvent("MESSAGE_DELETED", mapOf("messageId" to event.messageId)))
    }

    private fun send(channelId: Long, event: ChatSocketEvent) {
        messagingTemplate.convertAndSend("/topic/channels/$channelId", event)
    }
}
