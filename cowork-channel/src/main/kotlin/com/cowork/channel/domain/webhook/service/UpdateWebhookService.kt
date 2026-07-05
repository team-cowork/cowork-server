package com.cowork.channel.domain.webhook.service

import com.cowork.channel.domain.webhook.presentation.data.request.UpdateWebhookRequest
import com.cowork.channel.domain.webhook.presentation.data.response.WebhookResponse

interface UpdateWebhookService {
    fun updateWebhook(userId: Long, channelId: Long, webhookId: Long, request: UpdateWebhookRequest): WebhookResponse
}
