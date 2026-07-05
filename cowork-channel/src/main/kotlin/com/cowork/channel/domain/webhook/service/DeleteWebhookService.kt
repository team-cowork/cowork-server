package com.cowork.channel.domain.webhook.service

interface DeleteWebhookService {
    fun deleteWebhook(userId: Long, channelId: Long, webhookId: Long)
}
