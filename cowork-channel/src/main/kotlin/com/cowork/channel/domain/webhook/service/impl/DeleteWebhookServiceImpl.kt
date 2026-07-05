package com.cowork.channel.domain.webhook.service.impl

import com.cowork.channel.domain.webhook.repository.WebhookRepository
import com.cowork.channel.domain.webhook.service.DeleteWebhookService
import com.cowork.channel.domain.webhook.service.WebhookAccessGuard
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional
class DeleteWebhookServiceImpl(
    private val webhookRepository: WebhookRepository,
    private val webhookAccessGuard: WebhookAccessGuard,
) : DeleteWebhookService {

    override fun deleteWebhook(userId: Long, channelId: Long, webhookId: Long) {
        webhookAccessGuard.requireWebhookManager(channelId, userId)
        val webhook = webhookAccessGuard.findWebhookOrThrow(webhookId)
        if (webhook.channelId != channelId) {
            throw ExpectedException("해당 채널의 웹훅이 아닙니다.", HttpStatus.BAD_REQUEST)
        }
        webhookRepository.delete(webhook)
    }
}
