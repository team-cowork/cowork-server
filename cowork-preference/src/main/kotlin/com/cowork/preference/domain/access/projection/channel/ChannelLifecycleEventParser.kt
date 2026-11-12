package com.cowork.preference.domain.access.projection.channel

import com.cowork.preference.domain.access.model.InitialChannelRolePolicySeed
import io.vertx.core.json.JsonObject
import java.time.Instant

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

        val isPrivate = when (val value = payload.getValue("isPrivate")) {
            null -> null
            is Boolean -> value
            else -> error("isPrivate must be a boolean")
        }

        // 필드 추가 전에 발행된 이벤트에는 값이 없으며 활성 채널로 본다.
        val archived = when (val value = payload.getValue("archived")) {
            null -> false
            is Boolean -> value
            else -> error("archived must be a boolean")
        }

        val event = ChannelLifecycleEvent(
            channelId,
            teamId,
            eventType == "DELETED",
            occurredAt,
            isPrivate,
            payload.getString("channelType") ?: payload.getString("type"),
            payload.getJsonObject("initialRolePolicySeed")?.let(InitialChannelRolePolicySeed::fromJson),
            archived = archived,
        )
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
