package com.cowork.channel.dto

import com.cowork.channel.domain.Webhook
import java.time.LocalDateTime

data class CreateWebhookRequest(
    val name: String,
    val avatarUrl: String? = null,
)

data class UpdateWebhookRequest(
    val name: String? = null,
    val avatarUrl: String? = null,
    val rotateToken: Boolean = false,
)

data class WebhookResponse(
    val id: Long,
    val channelId: Long,
    val name: String,
    val avatarUrl: String?,
    val executeUrl: String,
    val createdBy: Long,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun of(webhook: Webhook, baseUrl: String) = WebhookResponse(
            id = webhook.id,
            channelId = webhook.channelId,
            name = webhook.name,
            avatarUrl = webhook.avatarUrl,
            executeUrl = "${baseUrl.trimEnd('/')}/webhooks/${webhook.id}/${webhook.token}",
            createdBy = webhook.createdBy,
            createdAt = webhook.createdAt,
        )
    }
}
