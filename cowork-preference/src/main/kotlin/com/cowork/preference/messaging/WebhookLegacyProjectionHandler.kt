package com.cowork.preference.messaging

import com.cowork.preference.domain.Webhook
import com.cowork.preference.repository.ChannelLifecycleProjectionRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.ProjectionCheckpoint
import com.cowork.preference.repository.ProjectionCheckpointRepository
import com.cowork.preference.repository.WebhookRepository
import com.cowork.preference.service.ProjectionNotReadyException
import io.vertx.core.json.JsonObject
import java.time.Instant

object WebhookLegacyParser {
    fun parse(key: String?, value: String?): Webhook {
        require(!value.isNullOrBlank()) { "legacy webhook state is required" }
        val json = JsonObject(value)
        require(json.getValue("schemaVersion") == 1) { "unsupported legacy webhook schemaVersion" }
        require(json.getString("eventType") == "WEBHOOK_LEGACY_STATE") { "unsupported legacy webhook eventType" }
        val id = positiveId(json, "webhookId")
        require(key == id.toString()) { "legacy webhook key must equal webhookId" }
        val channelId = positiveId(json, "channelId")
        val createdBy = positiveId(json, "createdBy")
        val name = (json.getValue("name") as? String)?.takeIf { it.isNotBlank() && it.length <= 100 }
            ?: error("legacy webhook name is invalid")
        val avatar = json.getValue("avatarUrl")
        require(avatar == null || avatar is String && avatar.length <= 512) { "legacy webhook avatarUrl is invalid" }
        val secure = json.getValue("isSecure") as? Boolean ?: error("legacy webhook isSecure is required")
        val hash = json.getValue("secretHash")
        require(if (secure) hash is String && hash.matches(Regex("[0-9a-f]{64}")) else hash == null) {
            "legacy webhook secretHash is invalid"
        }
        require(json.getValue("snapshot") is Boolean) { "legacy webhook snapshot flag is required" }
        val createdAt = Instant.parse(json.getString("createdAt"))
        val occurredAt = Instant.parse(json.getString("occurredAt"))
        return Webhook(id, channelId, name, avatar as? String, secure, hash as? String, true, false,
            createdBy, createdAt, occurredAt, null)
    }

    private fun positiveId(json: JsonObject, field: String): Long {
        val number = json.getValue(field) as? Number ?: error("legacy webhook $field must be numeric")
        return number.toString().toLongOrNull()?.takeIf { it > 0 } ?: error("legacy webhook $field must be positive")
    }
}

class WebhookLegacyProjectionHandler(
    private val repository: WebhookRepository,
    private val channels: ChannelLifecycleProjectionRepository,
    private val checkpoints: ProjectionCheckpointRepository,
    private val outbox: PreferenceOutboxRepository,
    private val channelReadiness: ProjectionReadinessView,
) : ProjectionRecordHandler {
    override suspend fun apply(checkpoint: ProjectionCheckpoint, key: String?, value: String?): String? {
        val parsed = runCatching { WebhookLegacyParser.parse(key, value) }
        val webhook = parsed.getOrNull() ?: return parsed.exceptionOrNull()?.message ?: "invalid legacy webhook state"
        if (!channelReadiness.isReady) throw ProjectionNotReadyException("channel lifecycle projection is not ready for webhook import")
        checkpoints.inTransaction(checkpoint) { connection ->
            val channel = channels.findForUpdate(connection, webhook.channelId)
                ?: throw ProjectionNotReadyException("legacy webhook channel projection is missing")
            val imported = if (channel.deleted) webhook.copy(
                deleted = true, isActive = false, secretHash = null,
                updatedAt = maxOf(webhook.updatedAt, channel.sourceOccurredAt),
            ) else webhook.copy(isActive = repository.legacyActive(connection, webhook.channelId))
            repository.importLegacy(connection, imported)?.let {
                outbox.enqueue(connection, PreferenceEvents.webhookState(it, snapshot = false))
            }
        }
        return null
    }
}
