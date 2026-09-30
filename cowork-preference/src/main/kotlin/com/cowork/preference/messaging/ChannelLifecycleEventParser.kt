package com.cowork.preference.messaging

import io.vertx.core.json.JsonObject
import java.time.Instant

data class ChannelLifecycleEvent(
    val channelId: Long,
    val teamId: Long?,
    val deleted: Boolean,
    val occurredAt: Instant,
)

sealed interface ChannelLifecycleRecordDecision {
    data class Apply(val event: ChannelLifecycleEvent) : ChannelLifecycleRecordDecision

    data class Quarantine(val reason: String) : ChannelLifecycleRecordDecision
}

object ChannelLifecycleEventParser {
    fun parse(key: String?, value: String?): ChannelLifecycleRecordDecision = runCatching {
        require(!value.isNullOrBlank()) { "payload is required" }
        val payload = JsonObject(value)
        val eventType = payload.getValue("eventType") as? String
            ?: error("eventType must be a string")
        require(eventType in EVENT_TYPES) { "unsupported eventType '$eventType'" }

        val channelId = positiveLong(payload.getValue("channelId"), "channelId")
        require(key == channelId.toString()) { "record key must equal channelId" }
        val teamId = payload.getValue("teamId")?.let { positiveLong(it, "teamId") }
        val occurredAtValue = payload.getValue("occurredAt") as? String
            ?: error("occurredAt must be an RFC3339 string")
        val occurredAt = runCatching { Instant.parse(occurredAtValue) }
            .getOrElse { error("occurredAt must be an RFC3339 instant") }

        val event = ChannelLifecycleEvent(channelId, teamId, eventType == "DELETED", occurredAt)
        ChannelLifecycleRecordDecision.Apply(event)
    }.getOrElse { error ->
        ChannelLifecycleRecordDecision.Quarantine(error.message ?: "invalid channel lifecycle record")
    }

    private fun positiveLong(value: Any?, field: String): Long {
        val number = value as? Number ?: error("$field must be an integer")
        val parsed = number.toString().toLongOrNull() ?: error("$field must be an integer")
        require(parsed > 0) { "$field must be positive" }
        return parsed
    }

    private val EVENT_TYPES = setOf("CREATED", "UPDATED", "DELETED")
}
