package com.cowork.preference.messaging

import io.vertx.core.json.JsonObject
import java.time.Instant

data class TeamMemberEvent(
    val eventType: String,
    val teamId: Long,
    val userId: Long,
    val role: String,
    val occurredAt: Instant,
)

enum class TeamMemberCleanupScope {
    MEMBER,
    TEAM,
}

object TeamMemberCleanupPolicy {
    fun scope(event: TeamMemberEvent): TeamMemberCleanupScope =
        if (event.role == "OWNER") TeamMemberCleanupScope.TEAM else TeamMemberCleanupScope.MEMBER
}

sealed interface TeamMemberRecordDecision {
    data class Apply(val event: TeamMemberEvent) : TeamMemberRecordDecision

    data class Quarantine(val reason: String) : TeamMemberRecordDecision
}

object TeamMemberEventParser {
    fun parse(key: String?, value: String?): TeamMemberRecordDecision = runCatching {
        require(!value.isNullOrBlank()) { "payload is required" }
        val payload = JsonObject(value)
        val eventType = payload.getValue("eventType") as? String
            ?: error("eventType must be a string")
        require(eventType in EVENT_TYPES) { "unsupported eventType '$eventType'" }

        val teamId = positiveLong(payload.getValue("teamId"), "teamId")
        val userId = positiveLong(payload.getValue("userId"), "userId")
        require(key == "$teamId:$userId") { "record key must equal teamId:userId" }
        val role = payload.getValue("role") as? String ?: error("role must be a string")
        require(role in ROLES) { "unsupported role '$role'" }
        val occurredAtValue = payload.getValue("occurredAt") as? String
            ?: error("occurredAt must be an RFC3339 string")
        val occurredAt = runCatching { Instant.parse(occurredAtValue) }
            .getOrElse { error("occurredAt must be an RFC3339 instant") }

        val event = TeamMemberEvent(eventType, teamId, userId, role, occurredAt)
        TeamMemberRecordDecision.Apply(event)
    }.getOrElse { error ->
        TeamMemberRecordDecision.Quarantine(error.message ?: "invalid team member record")
    }

    private fun positiveLong(value: Any?, field: String): Long {
        val number = value as? Number ?: error("$field must be an integer")
        val parsed = number.toString().toLongOrNull() ?: error("$field must be an integer")
        require(parsed > 0) { "$field must be positive" }
        return parsed
    }

    private val EVENT_TYPES = setOf("UPSERT", "DELETE")
    private val ROLES = setOf("OWNER", "ADMIN", "MEMBER")
}
