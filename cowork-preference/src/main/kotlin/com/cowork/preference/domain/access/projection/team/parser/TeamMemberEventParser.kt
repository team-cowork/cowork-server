package com.cowork.preference.domain.access.projection.team.parser

import com.cowork.preference.domain.access.projection.ProjectionEventInput
import com.cowork.preference.domain.access.projection.team.model.TeamMemberEvent
import com.cowork.preference.domain.access.projection.team.model.TeamMemberRecordDecision
import io.vertx.core.json.JsonObject
import java.time.Instant

object TeamMemberEventParser {
    fun parse(key: String?, value: String?): TeamMemberRecordDecision = runCatching {
        require(!value.isNullOrBlank()) { "payload is required" }
        val payload = JsonObject(value)
        val eventType = payload.getValue("eventType") as? String
            ?: error("eventType must be a string")
        require(eventType in EVENT_TYPES) { "unsupported eventType '$eventType'" }

        val teamId = ProjectionEventInput.positiveLong(payload.getValue("teamId"), "teamId")
        val userId = ProjectionEventInput.positiveLong(payload.getValue("userId"), "userId")
        require(key == "$teamId:$userId") { "record key must equal teamId:userId" }
        val role = payload.getValue("role") as? String ?: error("role must be a string")
        require(role in ROLES) { "unsupported role '$role'" }
        val occurredAt = ProjectionEventInput.occurredAt(payload)

        val authorizationVersion = payload.getValue("authorizationVersion").let { value ->
            require(value is String) { "authorizationVersion must be an RFC3339 string" }
            Instant.parse(value).also {
                require(it > Instant.EPOCH && it <= occurredAt) { "authorizationVersion is outside the event version" }
            }
        }
        val event = TeamMemberEvent(eventType, teamId, userId, role, occurredAt, authorizationVersion)
        TeamMemberRecordDecision.Apply(event)
    }.getOrElse { error ->
        TeamMemberRecordDecision.Quarantine(error.message ?: "invalid team member record")
    }

    private val EVENT_TYPES = setOf("UPSERT", "DELETE")
    private val ROLES = setOf("OWNER", "ADMIN", "MEMBER")
}
