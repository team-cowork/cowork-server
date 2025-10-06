package com.cowork.chat.service

import com.cowork.channel.event.ChannelDeletedEvent
import com.cowork.chat.repository.MessageAttachmentRepository
import com.cowork.chat.repository.MessageRepository
import com.cowork.global.storage.StorageService
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class ChatLifecycleListener(
    private val messageRepository: MessageRepository,
    private val messageAttachmentRepository: MessageAttachmentRepository,
    private val storageService: StorageService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    fun onChannelDeleted(event: ChannelDeletedEvent) {
        val objectKeys = messageAttachmentRepository.findObjectKeysByChannelId(event.channelId)
        messageAttachmentRepository.deleteAllByChannelId(event.channelId)
        messageRepository.deleteAllByChannelId(event.channelId)
        objectKeys.forEach { key ->
            runCatching { storageService.delete(key) }
                .onFailure { log.warn("Fail to delete chat attachment {}", key, it) }
        }
    }
}
