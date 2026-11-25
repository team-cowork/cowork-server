package com.cowork.preference.domain.access.projection.channel.parser

import com.cowork.preference.domain.access.model.InitialChannelRolePolicySeed
import com.cowork.preference.domain.access.projection.ProjectionEventInput
import com.cowork.preference.domain.access.projection.channel.model.ChannelLifecycleEvent
import com.cowork.preference.domain.access.projection.channel.model.ChannelLifecycleRecordDecision
import io.vertx.core.json.JsonObject

object ChannelLifecycleEventParser {
    fun parse(key: String?, value: String?): ChannelLifecycleRecordDecision = runCatching {
        require(!value.isNullOrBlank()) { "payload is required" }
        val payload = JsonObject(value)
        val eventType = payload.getValue("eventType") as? String
            ?: error("eventType must be a string")
        require(eventType in EVENT_TYPES) { "unsupported eventType '$eventType'" }

        val channelId = ProjectionEventInput.positiveLong(payload.getValue("channelId"), "channelId")
        require(key == channelId.toString()) { "record key must equal channelId" }
        val teamId = payload.getValue("teamId")?.let { ProjectionEventInput.positiveLong(it, "teamId") }
        val occurredAt = ProjectionEventInput.occurredAt(payload)

        val isPrivate = payload.getValue("isPrivate") as? Boolean ?: error("isPrivate must be a boolean")
        val channelType = (payload.getValue("type") as? String)?.takeIf { it.isNotBlank() }
            ?: error("type must be a non-blank string")
        val archived = payload.getValue("archived") as? Boolean ?: error("archived must be a boolean")

        val event = ChannelLifecycleEvent(
            channelId,
            teamId,
            eventType == "DELETED",
            occurredAt,
            isPrivate,
            channelType,
            payload.getJsonObject("initialRolePolicySeed")?.let(InitialChannelRolePolicySeed::fromJson),
            archived = archived,
        )
        ChannelLifecycleRecordDecision.Apply(event)
    }.getOrElse { error ->
        ChannelLifecycleRecordDecision.Quarantine(error.message ?: "invalid channel lifecycle record")
    }

    private val EVENT_TYPES = setOf("CREATED", "UPDATED", "DELETED")
}
