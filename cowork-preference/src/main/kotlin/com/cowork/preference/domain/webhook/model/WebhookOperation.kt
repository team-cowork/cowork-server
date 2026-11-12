package com.cowork.preference.domain.webhook.model

import io.vertx.core.json.JsonObject
import java.time.Instant

data class WebhookOperation(
    val requestHash: String,
    val response: JsonObject,
    val secretCiphertext: String?,
    val createdAt: Instant,
)
