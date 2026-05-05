package com.cowork.channel.dto

import com.cowork.channel.domain.Webhook
import java.time.LocalDateTime

data class WebhookResponse(
    val id: Long,
    val channelId: Long,
    val name: String,
    val isSecure: Boolean,
    val token: String?,
    val avatarUrl: String?,
    val createdBy: Long,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun of(webhook: Webhook) = WebhookResponse(
            id = webhook.id,
            channelId = webhook.channelId,
            name = webhook.name,
            isSecure = webhook.isSecure,
            token = webhook.token,
            avatarUrl = webhook.avatarUrl,
            createdBy = webhook.createdBy,
            createdAt = webhook.createdAt,
            updatedAt = webhook.updatedAt,
        )
    }
}
