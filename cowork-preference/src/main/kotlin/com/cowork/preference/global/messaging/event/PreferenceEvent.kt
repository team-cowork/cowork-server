package com.cowork.preference.global.messaging.event

import io.vertx.core.json.JsonObject
import java.time.Instant

data class PreferenceEvent(
    val topic: String,
    val key: String,
    val payload: JsonObject,
    val occurredAt: Instant,
    val partition: Int? = null,
)
