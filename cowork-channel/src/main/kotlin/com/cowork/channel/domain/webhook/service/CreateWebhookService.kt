package com.cowork.channel.domain.webhook.service

import com.cowork.channel.domain.webhook.presentation.data.request.CreateWebhookRequest
import com.cowork.channel.domain.webhook.presentation.data.response.WebhookResponse

interface CreateWebhookService {
    fun createWebhook(userId: Long, channelId: Long, request: CreateWebhookRequest): WebhookResponse
}
