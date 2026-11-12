package com.cowork.preference.domain.access.projection.dm.parser

import com.cowork.preference.domain.access.projection.ProjectionEventInput
import com.cowork.preference.domain.access.projection.dm.model.DmParticipantEvent
import com.cowork.preference.domain.access.projection.dm.model.DmParticipantRecordDecision
import io.vertx.core.json.JsonObject

object DmParticipantEventParser {
    fun parse(key: String?, value: String?): DmParticipantRecordDecision = runCatching {
        require(!value.isNullOrBlank()) { "payload is required" }
        val payload = JsonObject(value)
        require(payload.getString("channelType") == "DM" && payload.getValue("teamId") == null) {
            "only DM participation is supported"
        }
        val eventType = payload.getValue("eventType") as? String
            ?: error("eventType must be a string")
        require(eventType in EVENT_TYPES) { "unsupported eventType '$eventType'" }

        val channelId = ProjectionEventInput.positiveLong(payload.getValue("channelId"), "channelId")
        val userId = ProjectionEventInput.positiveLong(payload.getValue("userId"), "userId")
        require(key == "$channelId:$userId") { "record key must equal channelId:userId" }
        val occurredAt = ProjectionEventInput.occurredAt(payload)

        DmParticipantRecordDecision.Apply(DmParticipantEvent(channelId, userId, eventType == "LEAVE", occurredAt))
    }.getOrElse { error ->
        DmParticipantRecordDecision.Quarantine(error.message ?: "invalid channel member record")
    }

    private val EVENT_TYPES = setOf("JOIN", "LEAVE")
}
