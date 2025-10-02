package com.cowork.channel.service

import com.cowork.auth.service.TokenHasher
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.domain.Webhook
import com.cowork.channel.dto.CreateWebhookRequest
import com.cowork.channel.dto.UpdateWebhookRequest
import com.cowork.channel.dto.WebhookResponse
import com.cowork.channel.event.ChannelDeletedEvent
import com.cowork.channel.repository.WebhookRepository
import com.cowork.global.error.BusinessException
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest

@Service
class WebhookService(
    private val webhookRepository: WebhookRepository,
    private val channelAccessChecker: ChannelAccessChecker,
    @Value("\${cowork.public-base-url}") private val publicBaseUrl: String,
) {

    @Transactional
    fun create(channelId: Long, userId: Long, request: CreateWebhookRequest): WebhookResponse {
        val channel = channelAccessChecker.requireManageable(channelId, userId)
        channelAccessChecker.requireViewType(channel, ChannelViewType.TEXT, ChannelViewType.WEBHOOK)
        val webhook = webhookRepository.save(
            Webhook(
                channelId = channelId,
                name = request.name,
                avatarUrl = request.avatarUrl,
                token = TokenHasher.generate(),
                createdBy = userId,
            ),
        )
        return WebhookResponse.of(webhook, publicBaseUrl)
    }
}
