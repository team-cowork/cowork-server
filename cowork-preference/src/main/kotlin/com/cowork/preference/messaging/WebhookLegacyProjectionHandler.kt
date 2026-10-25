package com.cowork.preference.messaging

import com.cowork.preference.domain.LegacyWebhookManifest
import com.cowork.preference.domain.LegacyWebhookObservation
import com.cowork.preference.domain.Webhook
import com.cowork.preference.domain.WebhookMigrationConflict
import com.cowork.preference.domain.WebhookMigrationIds
import com.cowork.preference.repository.ChannelLifecycleProjectionRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.ProjectionCheckpoint
import com.cowork.preference.repository.ProjectionCheckpointRepository
import com.cowork.preference.repository.WebhookRepository
import com.cowork.preference.service.LegacyWebhookMigrationService
import com.cowork.preference.service.ProjectionNotReadyException
import io.vertx.core.json.JsonObject
import java.time.Instant
import java.util.UUID

sealed interface LegacyWebhookRecord {
    data class State(val observation: LegacyWebhookObservation) : LegacyWebhookRecord
    data class Manifest(val manifest: LegacyWebhookManifest) : LegacyWebhookRecord
}

object WebhookLegacyParser {
    fun parse(key: String?, value: String?): LegacyWebhookRecord {
        require(!value.isNullOrBlank()) { "legacy webhook state is required" }
        val json = JsonObject(value)
        require(json.getValue("schemaVersion") == 1) { "unsupported legacy webhook schemaVersion" }
        require(json.getValue("snapshot") == true) { "legacy webhook snapshot flag must be true" }
        val snapshotId = json.getString("snapshotId")
        require(UUID.fromString(snapshotId).toString() == snapshotId) { "legacy webhook snapshotId must be a UUID" }
        val observedAt = Instant.parse(json.getString("observedAt"))
        val channelId = positiveId(json.getValue("channelId"), "channelId")
        return when (json.getString("eventType")) {
            "WEBHOOK_LEGACY_STATE" -> LegacyWebhookRecord.State(LegacyWebhookObservation(state(key, json, channelId), snapshotId, observedAt))
            "WEBHOOK_LEGACY_MANIFEST" -> {
                val index = integer(json, "chunkIndex")
                val count = integer(json, "chunkCount")
                require(count > 0 && index in 0 until count) { "legacy webhook manifest chunk is invalid" }
                require(key == "channel:$channelId:$index") { "legacy webhook manifest key is invalid" }
                val ids = json.getJsonArray("webhookIds").map { legacyId(it) }
                require(ids.size <= 1000 && ids.size == ids.toSet().size) { "legacy webhook manifest IDs are invalid" }
                LegacyWebhookRecord.Manifest(LegacyWebhookManifest(channelId, ids.toSet(), snapshotId, observedAt, index, count))
            }
            else -> error("unsupported legacy webhook eventType")
        }
    }

    private fun state(key: String?, json: JsonObject, channelId: Long): Webhook {
        val id = legacyId(json.getValue("webhookId"))
        require(key == id.toString()) { "legacy webhook key must equal webhookId" }
        val createdBy = positiveId(json.getValue("createdBy"), "createdBy")
        val name = (json.getValue("name") as? String)?.takeIf { it.isNotBlank() && it.length <= 100 }
            ?: error("legacy webhook name is invalid")
        val avatar = json.getValue("avatarUrl")
        require(avatar == null || avatar is String && avatar.length <= 512) { "legacy webhook avatarUrl is invalid" }
        val secure = json.getValue("isSecure") as? Boolean ?: error("legacy webhook isSecure is required")
        val hash = json.getValue("secretHash")
        require(if (secure) hash is String && hash.matches(Regex("[0-9a-f]{64}")) else hash == null) {
            "legacy webhook secretHash is invalid"
        }
        val createdAt = Instant.parse(json.getString("createdAt"))
        val occurredAt = Instant.parse(json.getString("occurredAt"))
        return Webhook(id, channelId, name, avatar, secure, hash as? String, true, false,
            createdBy, createdAt, occurredAt, null)
    }

    private fun legacyId(value: Any?): Long = positiveId(value, "webhookId").also {
        require(WebhookMigrationIds.isLegacy(it)) { "legacy webhook ID overlaps owner range" }
    }

    private fun positiveId(value: Any?, field: String): Long {
        val number = value as? Number ?: error("legacy webhook $field must be numeric")
        return number.toString().toLongOrNull()?.takeIf { it in 1..WebhookMigrationIds.MAX_SAFE_ID }
            ?: error("legacy webhook $field must be a positive safe integer")
    }

    private fun integer(json: JsonObject, field: String): Int = (json.getValue(field) as? Number)?.toString()?.toIntOrNull()
        ?: error("legacy webhook $field must be an integer")
}

// TODO(#625): 임시 rollback 재이관 계약이다. v20261026.0 이후 추가 릴리즈까지 rollback이 없거나
// 확인이 어려우면 2026-11-01(2026-10-24 + 8일) 이후 전체 이관·격리 해소와 함께 제거 가능하다.
// 자동 만료하지 않으며 publisher/readiness 의존을 함께 제거하고 ID sequence는 낮추지 않는다.
class WebhookLegacyProjectionHandler(
    repository: WebhookRepository,
    channels: ChannelLifecycleProjectionRepository,
    private val checkpoints: ProjectionCheckpointRepository,
    outbox: PreferenceOutboxRepository,
    private val channelReadiness: ProjectionReadinessView,
) : ProjectionRecordHandler {
    private val migration = LegacyWebhookMigrationService(repository, channels, outbox)

    override suspend fun apply(checkpoint: ProjectionCheckpoint, key: String?, value: String?): String? {
        val parsed = runCatching { WebhookLegacyParser.parse(key, value) }
        val record = parsed.getOrNull() ?: return parsed.exceptionOrNull()?.message ?: "invalid legacy webhook state"
        if (!channelReadiness.isReady) throw ProjectionNotReadyException("channel lifecycle projection is not ready for webhook import")
        return try {
            checkpoints.inTransaction(checkpoint) { connection ->
                when (record) {
                    is LegacyWebhookRecord.State -> migration.observe(connection, record.observation)
                    is LegacyWebhookRecord.Manifest -> migration.manifest(connection, record.manifest)
                }
            }
            null
        } catch (conflict: WebhookMigrationConflict) {
            conflict.message ?: "conflicting legacy webhook state"
        }
    }
}
