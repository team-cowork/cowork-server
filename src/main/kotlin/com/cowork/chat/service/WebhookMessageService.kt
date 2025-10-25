package com.cowork.chat.service

import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.channel.service.WebhookService
import com.cowork.chat.domain.Message
import com.cowork.chat.domain.MessageType
import com.cowork.chat.dto.MessageResponse
import com.cowork.chat.event.MessageCreatedEvent
import com.cowork.chat.repository.MessageRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WebhookMessageService(
    private val webhookService: WebhookService,
    private val channelAccessChecker: ChannelAccessChecker,
    private val messageRepository: MessageRepository,
    private val messageAssembler: MessageAssembler,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @Transactional
    fun execute(webhookId: Long, token: String, content: String, username: String?): MessageResponse {
        val webhook = webhookService.authenticate(webhookId, token)
        val channel = channelAccessChecker.findChannel(webhook.channelId)
        val message = messageRepository.save(
            Message(
                channelId = channel.id,
                senderId = null,
                type = MessageType.TEXT,
                content = content.trim(),
                webhookId = webhook.id,
                senderName = username?.takeIf { it.isNotBlank() }?.take(100) ?: webhook.name,
            ),
        )
        val response = messageAssembler.assemble(message)
        eventPublisher.publishEvent(MessageCreatedEvent(channel.teamId, response, emptySet()))
        return response
    }
}
