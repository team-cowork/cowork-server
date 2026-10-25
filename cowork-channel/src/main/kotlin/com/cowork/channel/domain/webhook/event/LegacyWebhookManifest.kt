package com.cowork.channel.domain.webhook.event

import java.time.Instant

data class LegacyWebhookManifest(
    val eventType: String = "WEBHOOK_LEGACY_MANIFEST",
    val schemaVersion: Int = 1,
    val channelId: Long,
    val webhookIds: List<Long>,
    val snapshotId: String,
    val observedAt: Instant,
    val chunkIndex: Int,
    val chunkCount: Int,
    val snapshot: Boolean = true,
) {
    fun key(): String = "channel:$channelId:$chunkIndex"

    companion object {
        const val MAX_IDS_PER_CHUNK = 1000

        fun chunks(
            channelId: Long,
            webhookIds: List<Long>,
            snapshotId: String,
            observedAt: Instant,
        ): List<LegacyWebhookManifest> {
            val chunks = webhookIds.chunked(MAX_IDS_PER_CHUNK).ifEmpty { listOf(emptyList()) }
            return chunks.mapIndexed { index, ids ->
                LegacyWebhookManifest(
                    channelId = channelId,
                    webhookIds = ids,
                    snapshotId = snapshotId,
                    observedAt = observedAt,
                    chunkIndex = index,
                    chunkCount = chunks.size,
                )
            }
        }
    }
}
