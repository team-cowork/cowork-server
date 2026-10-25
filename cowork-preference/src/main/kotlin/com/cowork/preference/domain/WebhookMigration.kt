package com.cowork.preference.domain

import io.vertx.core.json.JsonObject
import java.time.Instant

// TODO(#625): 임시 재이관 보정. 구형 Channel rollback의 수정·삭제는 반영하되 Preference 수정은 보존한다.
// v20261026.0 이후 추가 릴리즈가 배포되고 그동안 rollback이 없으면 제거할 수 있다.
// 확인하기 어려우면 2026-11-01(2026-10-24 + 8일) 이후 제거할 수 있으며 자동 만료하지 않는다.
// 전체 이관·격리 해소 후 importer/readiness/publisher 의존을 함께 제거하고, 이미 올린 ID sequence는 되돌리지 않는다.
object WebhookMigrationIds {
    const val FIRST_OWNER_ID = 4_503_599_627_370_496L
    const val MAX_SAFE_ID = 9_007_199_254_740_991L
    fun isLegacy(id: Long): Boolean = id in 1 until FIRST_OWNER_ID
    fun isOwner(id: Long): Boolean = id in FIRST_OWNER_ID..MAX_SAFE_ID
}

data class LegacyWebhookObservation(val webhook: Webhook, val snapshotId: String, val observedAt: Instant)

data class LegacyWebhookManifest(
    val channelId: Long,
    val webhookIds: Set<Long>,
    val snapshotId: String,
    val observedAt: Instant,
    val chunkIndex: Int = 0,
    val chunkCount: Int = 1,
) {
    fun json(): JsonObject = JsonObject().put("channelId", channelId).put("webhookIds", webhookIds.sorted())
        .put("snapshotId", snapshotId).put("observedAt", observedAt.toString())
        .put("chunkIndex", chunkIndex).put("chunkCount", chunkCount)

    companion object {
        fun from(json: JsonObject): LegacyWebhookManifest = LegacyWebhookManifest(
            json.getLong("channelId"), json.getJsonArray("webhookIds").map { (it as Number).toLong() }.toSet(),
            json.getString("snapshotId"), Instant.parse(json.getString("observedAt")),
            json.getInteger("chunkIndex", 0), json.getInteger("chunkCount", 1),
        )

        /** Old compacted tail chunks and incomplete generations never prove absence. */
        fun complete(parts: List<LegacyWebhookManifest>, expected: LegacyWebhookManifest): LegacyWebhookManifest? {
            val matching = parts.filter { it.channelId == expected.channelId && it.snapshotId == expected.snapshotId &&
                it.observedAt == expected.observedAt && it.chunkCount == expected.chunkCount }
            if (matching.size != expected.chunkCount || matching.map { it.chunkIndex }.toSet() != (0 until expected.chunkCount).toSet()) return null
            val ids = matching.flatMap { it.webhookIds }
            if (ids.size != ids.toSet().size) throw WebhookMigrationConflict("legacy manifest contains duplicate webhook IDs")
            return expected.copy(webhookIds = ids.toSet(), chunkIndex = 0, chunkCount = 1)
        }
    }
}

data class WebhookMigrationMetadata(
    val webhookId: Long,
    val channelId: Long,
    val createdBy: Long,
    val createdAt: Instant,
    val ownerAppliedUpdatedAt: Instant?,
    val lastSourceOccurredAt: Instant,
    val lastObservedAt: Instant,
    val lastSnapshotId: String,
    val sourceDeleted: Boolean,
    val sourceHash: String,
    val lastActive: Boolean = true,
) {
    fun json(): JsonObject = JsonObject().put("webhookId", webhookId).put("channelId", channelId)
        .put("createdBy", createdBy).put("createdAt", createdAt.toString())
        .put("ownerAppliedUpdatedAt", ownerAppliedUpdatedAt?.toString())
        .put("lastSourceOccurredAt", lastSourceOccurredAt.toString()).put("lastObservedAt", lastObservedAt.toString())
        .put("lastSnapshotId", lastSnapshotId).put("sourceDeleted", sourceDeleted).put("lastActive", lastActive)

    companion object {
        fun from(json: JsonObject, hash: String): WebhookMigrationMetadata = WebhookMigrationMetadata(
            json.getLong("webhookId"), json.getLong("channelId"), json.getLong("createdBy"),
            Instant.parse(json.getString("createdAt")), json.getString("ownerAppliedUpdatedAt")?.let(Instant::parse),
            Instant.parse(json.getString("lastSourceOccurredAt")), Instant.parse(json.getString("lastObservedAt")),
            json.getString("lastSnapshotId"), json.getBoolean("sourceDeleted"), hash, json.getBoolean("lastActive", true),
        )
    }
}

class WebhookMigrationConflict(message: String) : IllegalStateException(message)

enum class WebhookMigrationDecision { IMPORT, APPLY, REFRESH, PROTECT, IGNORE }

object WebhookMigrationPolicy {
    fun observe(current: Webhook?, metadata: WebhookMigrationMetadata?, observation: LegacyWebhookObservation,
        sourceHash: String, completed: LegacyWebhookManifest?): WebhookMigrationDecision {
        val source = observation.webhook
        if (completed != null && completed.observedAt >= observation.observedAt && source.id !in completed.webhookIds) {
            return WebhookMigrationDecision.IGNORE
        }
        if (metadata == null) return if (current == null) WebhookMigrationDecision.IMPORT else WebhookMigrationDecision.PROTECT
        if (metadata.channelId != source.channelId || metadata.createdBy != source.createdBy || metadata.createdAt != source.createdAt) {
            throw WebhookMigrationConflict("legacy webhook identity changed")
        }
        if (observation.observedAt < metadata.lastObservedAt || source.updatedAt < metadata.lastSourceOccurredAt) {
            return WebhookMigrationDecision.IGNORE
        }
        if (observation.observedAt == metadata.lastObservedAt) {
            if (metadata.sourceDeleted) return WebhookMigrationDecision.IGNORE
            if (observation.snapshotId != metadata.lastSnapshotId || sourceHash != metadata.sourceHash) {
                throw WebhookMigrationConflict("legacy webhook observation version conflicts")
            }
            return WebhookMigrationDecision.IGNORE
        }
        if (source.updatedAt == metadata.lastSourceOccurredAt && sourceHash != metadata.sourceHash && !metadata.sourceDeleted) {
            throw WebhookMigrationConflict("legacy webhook source mutation version conflicts")
        }
        if (current == null || metadata.ownerAppliedUpdatedAt == null || current.updatedAt != metadata.ownerAppliedUpdatedAt) {
            return WebhookMigrationDecision.PROTECT
        }
        return if (metadata.sourceDeleted || sourceHash != metadata.sourceHash) WebhookMigrationDecision.APPLY else WebhookMigrationDecision.REFRESH
    }

    fun delete(current: Webhook?, metadata: WebhookMigrationMetadata, manifest: LegacyWebhookManifest): WebhookMigrationDecision {
        if (metadata.webhookId in manifest.webhookIds || manifest.observedAt < metadata.lastObservedAt) return WebhookMigrationDecision.IGNORE
        if (current == null || metadata.ownerAppliedUpdatedAt == null || current.updatedAt != metadata.ownerAppliedUpdatedAt) {
            return WebhookMigrationDecision.PROTECT
        }
        return if (current.deleted) WebhookMigrationDecision.REFRESH else WebhookMigrationDecision.APPLY
    }
}
