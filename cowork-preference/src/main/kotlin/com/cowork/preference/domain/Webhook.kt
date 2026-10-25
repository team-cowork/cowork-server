package com.cowork.preference.domain

import io.vertx.core.json.JsonObject
import java.time.Duration
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
    /** Legacy source records have no execution identity until the owner inserts them. */
    val executionId: UUID? = null,
) {
    fun response(token: String? = null): JsonObject = JsonObject()
        .put("id", id).put("executionId", requireExecutionId().toString())
        .put("channelId", channelId).put("name", name)
        .put("avatarUrl", avatarUrl).put("isSecure", isSecure).put("token", token)
        .put("isActive", isActive).put("createdBy", createdBy)
        .put("createdAt", createdAt.toString()).put("updatedAt", updatedAt.toString())

    fun requireExecutionId(): UUID = checkNotNull(executionId) { "Webhook execution ID has not been assigned" }
}

object WebhookModePolicy {
    const val COOLDOWN_SECONDS = 30L

    /** The same mode is a no-op, including while a previous transition is cooling down. */
    fun retryAfterSeconds(webhook: Webhook, target: Boolean, now: Instant): Long {
        if (webhook.isSecure == target) return 0
        val previous = webhook.modeChangedAt ?: return 0
        val remaining = Duration.between(now, previous.plusSeconds(COOLDOWN_SECONDS))
        return if (remaining.isNegative || remaining.isZero) 0 else remaining.seconds + if (remaining.nano > 0) 1 else 0
    }
}

class WebhookRequestException(val status: Int, message: String, val retryAfter: Long? = null, val nextAllowedAt: Instant? = null) :
    RuntimeException(message)

object WebhookInput {
    fun create(body: JsonObject): JsonObject {
        allowed(body, setOf("name", "avatarUrl", "isSecure", "isActive"))
        return JsonObject().put("name", name(body.getValue("name")))
            .put("avatarUrl", avatar(body.getValue("avatarUrl")))
            .put("isSecure", boolean(body, "isSecure", false))
            .put("isActive", boolean(body, "isActive", true))
    }

    fun patch(body: JsonObject): JsonObject {
        if (body.containsKey("isSecure")) invalid("보안 모드는 전용 API에서 변경해야 합니다.")
        allowed(body, setOf("name", "avatarUrl", "isActive"))
        return JsonObject().apply {
            if (body.containsKey("name")) put("name", name(body.getValue("name")))
            if (body.containsKey("avatarUrl")) put("avatarUrl", avatar(body.getValue("avatarUrl")))
            if (body.containsKey("isActive")) put("isActive", boolean(body, "isActive", true))
        }
    }

    fun securityMode(body: JsonObject): Boolean {
        allowed(body, setOf("isSecure"))
        return body.getValue("isSecure") as? Boolean ?: invalid("isSecure는 boolean이어야 합니다.")
    }

    fun idempotencyKey(value: String?): String = value?.takeIf { it.isNotBlank() && it.length <= 128 }
        ?: invalid("128자 이하의 Idempotency-Key가 필요합니다.")

    private fun name(value: Any?): String = (value as? String)?.takeIf { it.isNotBlank() && it.length <= 100 }
        ?: invalid("웹훅 이름은 1자 이상 100자 이하여야 합니다.")

    private fun avatar(value: Any?): String? {
        if (value == null) return null
        return (value as? String)?.takeIf { it.length <= 512 }
            ?: invalid("웹훅 아바타 주소는 512자 이하여야 합니다.")
    }

    private fun boolean(body: JsonObject, key: String, default: Boolean): Boolean =
        if (!body.containsKey(key)) default else body.getValue(key) as? Boolean
            ?: invalid("$key 값은 boolean이어야 합니다.")

    private fun allowed(body: JsonObject, keys: Set<String>) {
        if (!keys.containsAll(body.fieldNames())) invalid("지원하지 않는 웹훅 설정입니다.")
    }

    private fun invalid(message: String): Nothing = throw WebhookRequestException(400, message)
}
