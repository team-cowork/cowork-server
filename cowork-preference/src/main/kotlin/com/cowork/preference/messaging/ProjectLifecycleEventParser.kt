package com.cowork.preference.messaging

import io.vertx.core.json.JsonObject
import java.time.Instant

/** project.event.v2에서 권한 판단에 필요한 소속 팀과 삭제 여부만 추린 것이다. */
data class ProjectLifecycleEvent(
    val projectId: Long,
    val teamId: Long,
    val deleted: Boolean,
    val occurredAt: Instant,
)

sealed interface ProjectLifecycleRecordDecision {
    data class Apply(val event: ProjectLifecycleEvent) : ProjectLifecycleRecordDecision

    data class Quarantine(val reason: String) : ProjectLifecycleRecordDecision
}

object ProjectLifecycleEventParser {
    fun parse(key: String?, value: String?): ProjectLifecycleRecordDecision = runCatching {
        require(!value.isNullOrBlank()) { "payload is required" }
        val payload = JsonObject(value)
        val eventType = payload.getValue("eventType") as? String
            ?: error("eventType must be a string")
        require(eventType in EVENT_TYPES) { "unsupported eventType '$eventType'" }

        val projectId = positiveLong(payload.getValue("projectId"), "projectId")
        require(key == projectId.toString()) { "record key must equal projectId" }
        val teamId = positiveLong(payload.getValue("teamId"), "teamId")
        val occurredAtValue = payload.getValue("occurredAt") as? String
            ?: error("occurredAt must be an RFC3339 string")
        val occurredAt = runCatching { Instant.parse(occurredAtValue) }
            .getOrElse { error("occurredAt must be an RFC3339 instant") }

        ProjectLifecycleRecordDecision.Apply(
            ProjectLifecycleEvent(projectId, teamId, eventType == "DELETED", occurredAt),
        )
    }.getOrElse { error ->
        ProjectLifecycleRecordDecision.Quarantine(error.message ?: "invalid project lifecycle record")
    }

    private fun positiveLong(value: Any?, field: String): Long {
        val number = value as? Number ?: error("$field must be an integer")
        val parsed = number.toString().toLongOrNull() ?: error("$field must be an integer")
        require(parsed > 0) { "$field must be positive" }
        return parsed
    }

    private val EVENT_TYPES = setOf("CREATED", "UPDATED", "DELETED")
}
