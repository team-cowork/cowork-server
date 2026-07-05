package com.cowork.channel.domain.webhook.service.impl

import com.cowork.channel.domain.webhook.presentation.data.request.UpdateWebhookRequest
import com.cowork.channel.domain.webhook.presentation.data.response.WebhookResponse
import com.cowork.channel.domain.webhook.service.UpdateWebhookService
import com.cowork.channel.domain.webhook.service.WebhookAccessGuard
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional
class UpdateWebhookServiceImpl(
    private val webhookAccessGuard: WebhookAccessGuard,
) : UpdateWebhookService {

    override fun updateWebhook(userId: Long, channelId: Long, webhookId: Long, request: UpdateWebhookRequest): WebhookResponse {
        webhookAccessGuard.requireWebhookManager(channelId, userId)
        val webhook = webhookAccessGuard.findWebhookOrThrow(webhookId)
        if (webhook.channelId != channelId) {
            throw ExpectedException("해당 채널의 웹훅이 아닙니다.", HttpStatus.BAD_REQUEST)
        }
        webhook.update(request.name, request.avatarUrl, request.isSecure)
        return WebhookResponse.of(webhook)
    }
}
