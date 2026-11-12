package com.cowork.preference.domain.webhook.validation

import com.cowork.preference.domain.webhook.exception.WebhookRequestException
import io.vertx.core.json.JsonObject

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

    private fun boolean(body: JsonObject, key: String, default: Boolean): Boolean = if (!body.containsKey(key)) {
        default
    } else {
        body.getValue(key) as? Boolean
            ?: invalid("$key 값은 boolean이어야 합니다.")
    }

    private fun allowed(body: JsonObject, keys: Set<String>) {
        if (!keys.containsAll(body.fieldNames())) invalid("지원하지 않는 웹훅 설정입니다.")
    }

    private fun invalid(message: String): Nothing = throw WebhookRequestException(400, message)
}
