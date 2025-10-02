package com.cowork.channel.dto

import com.cowork.channel.domain.Webhook
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class CreateWebhookRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,
    @field:Size(max = 512)
    val avatarUrl: String? = null,
)

data class UpdateWebhookRequest(
    @field:Size(min = 1, max = 100)
    val name: String? = null,
    @field:Size(max = 512)
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
