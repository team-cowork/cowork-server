package com.cowork.channel.domain.webhook.event

import com.cowork.channel.domain.webhook.entity.Webhook
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat

data class LegacyWebhookState(
    val eventType: String = "WEBHOOK_LEGACY_STATE",
    val schemaVersion: Int = 1,
    val webhookId: Long,
    val channelId: Long,
    val name: String,
    val avatarUrl: String?,
    val isSecure: Boolean,
    val secretHash: String?,
    val createdBy: Long,
    val createdAt: Instant,
    val occurredAt: Instant,
    val snapshotId: String,
    val observedAt: Instant,
    val snapshot: Boolean = true,
) {
    companion object {
        fun from(webhook: Webhook, snapshotId: String, observedAt: Instant): LegacyWebhookState = LegacyWebhookState(
            webhookId = webhook.id,
            channelId = webhook.channelId,
            name = webhook.name,
            avatarUrl = webhook.avatarUrl,
            isSecure = webhook.isSecure,
            secretHash = webhook.token?.takeIf { webhook.isSecure && it.isNotBlank() }?.let {
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8)))
            },
            createdBy = webhook.createdBy,
            createdAt = webhook.createdAt.toInstant(ZoneOffset.UTC),
            occurredAt = webhook.updatedAt.toInstant(ZoneOffset.UTC),
            snapshotId = snapshotId,
            observedAt = observedAt,
        )
    }
}
