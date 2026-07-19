package com.cowork.channel.domain.webhook.service.impl

import com.cowork.channel.domain.webhook.entity.Webhook
import com.cowork.channel.domain.webhook.presentation.data.request.CreateWebhookRequest
import com.cowork.channel.domain.webhook.presentation.data.response.WebhookResponse
import com.cowork.channel.domain.webhook.repository.WebhookRepository
import com.cowork.channel.domain.webhook.service.CreateWebhookService
import com.cowork.channel.domain.webhook.service.WebhookAccessGuard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class CreateWebhookServiceImpl(
    private val webhookRepository: WebhookRepository,
    private val webhookAccessGuard: WebhookAccessGuard,
) : CreateWebhookService {

    override fun createWebhook(userId: Long, channelId: Long, request: CreateWebhookRequest): WebhookResponse {
        webhookAccessGuard.requireWebhookManager(channelId, userId)

        val webhook = webhookRepository.save(
            Webhook(
                channelId = channelId,
                name = request.name,
                isSecure = request.isSecure,
                token = if (request.isSecure) UUID.randomUUID().toString() else null,
                avatarUrl = request.avatarUrl,
                createdBy = userId,
            ),
        )
        return WebhookResponse.of(webhook)
    }
}
