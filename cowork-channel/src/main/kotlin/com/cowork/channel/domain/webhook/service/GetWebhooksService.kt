package com.cowork.channel.domain.webhook.service

import com.cowork.channel.domain.webhook.presentation.data.response.WebhookResponse

interface GetWebhooksService {
    fun getWebhooks(userId: Long, channelId: Long): List<WebhookResponse>
}
