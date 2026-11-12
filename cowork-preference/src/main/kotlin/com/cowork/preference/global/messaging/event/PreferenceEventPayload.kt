package com.cowork.preference.global.messaging.event

import io.vertx.core.json.JsonObject
import java.time.Instant

internal object PreferenceEventPayload {
    fun basePayload(eventType: String, occurredAt: Instant): JsonObject = JsonObject()
        .put("eventType", eventType)
        .put("occurredAt", occurredAt.toString())
}
