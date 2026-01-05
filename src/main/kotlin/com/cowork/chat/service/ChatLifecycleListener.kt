package com.cowork.chat.service

import com.cowork.channel.event.ChannelDeletedEvent
import com.cowork.chat.repository.MessageAttachmentRepository
import com.cowork.chat.repository.MessageRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class ChatLifecycleListener(
    private val messageRepository: MessageRepository,
    private val messageAttachmentRepository: MessageAttachmentRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @EventListener
    fun onChannelDeleted(event: ChannelDeletedEvent) {
        val objectKeys = messageAttachmentRepository.findObjectKeysByChannelId(event.channelId)
        messageAttachmentRepository.deleteAllByChannelId(event.channelId)
        messageRepository.deleteAllByChannelId(event.channelId)
        if (objectKeys.isNotEmpty()) {
            eventPublisher.publishEvent(AttachmentsOrphanedEvent(objectKeys))
        }
    }
}
