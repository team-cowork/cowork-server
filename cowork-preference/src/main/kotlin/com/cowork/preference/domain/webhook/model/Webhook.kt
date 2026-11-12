package com.cowork.preference.domain.webhook.model

import io.vertx.core.json.JsonObject
import java.time.Instant
import java.util.UUID

/** Credentials are represented only by their one-way hash in authoritative state. */
data class Webhook(
    val id: Long,
    val channelId: Long,
    val name: String,
    val avatarUrl: String?,
    val isSecure: Boolean,
    val secretHash: String?,
    val isActive: Boolean,
    val deleted: Boolean,
    val createdBy: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val modeChangedAt: Instant?,
    val executionId: UUID,
) {
    fun response(token: String? = null): JsonObject = JsonObject()
        .put("id", id).put("executionId", executionId.toString())
        .put("channelId", channelId).put("name", name)
        .put("avatarUrl", avatarUrl).put("isSecure", isSecure).put("token", token)
        .put("isActive", isActive).put("createdBy", createdBy)
        .put("createdAt", createdAt.toString()).put("updatedAt", updatedAt.toString())
}
