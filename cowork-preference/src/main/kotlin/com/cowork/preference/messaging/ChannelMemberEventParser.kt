package com.cowork.preference.messaging

import io.vertx.core.json.JsonObject
import java.time.Instant

/** channel.member.event.v2에서 권한 판단에 필요한 값만 추린 것이다. role·teamId·channelType은 사용하지 않는다. */
data class ChannelMemberEvent(
    val channelId: Long,
    val userId: Long,
    val deleted: Boolean,
    val occurredAt: Instant,
)

sealed interface ChannelMemberRecordDecision {
    data class Apply(val event: ChannelMemberEvent) : ChannelMemberRecordDecision

    data class Quarantine(val reason: String) : ChannelMemberRecordDecision
}

object ChannelMemberEventParser {
    fun parse(key: String?, value: String?): ChannelMemberRecordDecision = runCatching {
        require(!value.isNullOrBlank()) { "payload is required" }
        val payload = JsonObject(value)
        val eventType = payload.getValue("eventType") as? String
            ?: error("eventType must be a string")
        require(eventType in EVENT_TYPES) { "unsupported eventType '$eventType'" }

        val channelId = positiveLong(payload.getValue("channelId"), "channelId")
        val userId = positiveLong(payload.getValue("userId"), "userId")
        require(key == "$channelId:$userId") { "record key must equal channelId:userId" }
        val occurredAtValue = payload.getValue("occurredAt") as? String
            ?: error("occurredAt must be an RFC3339 string")
        val occurredAt = runCatching { Instant.parse(occurredAtValue) }
            .getOrElse { error("occurredAt must be an RFC3339 instant") }

        ChannelMemberRecordDecision.Apply(ChannelMemberEvent(channelId, userId, eventType == "LEAVE", occurredAt))
    }.getOrElse { error ->
        ChannelMemberRecordDecision.Quarantine(error.message ?: "invalid channel member record")
    }

    private fun positiveLong(value: Any?, field: String): Long {
        val number = value as? Number ?: error("$field must be an integer")
        val parsed = number.toString().toLongOrNull() ?: error("$field must be an integer")
        require(parsed > 0) { "$field must be positive" }
        return parsed
    }

    private val EVENT_TYPES = setOf("JOIN", "LEAVE")
}
