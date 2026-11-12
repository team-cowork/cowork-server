package com.cowork.preference.domain.access.projection.project.parser

import com.cowork.preference.domain.access.model.InitialProjectReadGrant
import com.cowork.preference.domain.access.projection.ProjectionEventInput
import com.cowork.preference.domain.access.projection.project.model.ProjectLifecycleEvent
import com.cowork.preference.domain.access.projection.project.model.ProjectLifecycleRecordDecision
import io.vertx.core.json.JsonObject

object ProjectLifecycleEventParser {
    fun parse(key: String?, value: String?): ProjectLifecycleRecordDecision = runCatching {
        require(!value.isNullOrBlank()) { "payload is required" }
        val payload = JsonObject(value)
        val eventType = payload.getValue("eventType") as? String
            ?: error("eventType must be a string")
        require(eventType in EVENT_TYPES) { "unsupported eventType '$eventType'" }

        val projectId = ProjectionEventInput.positiveLong(payload.getValue("projectId"), "projectId")
        require(key == projectId.toString()) { "record key must equal projectId" }
        val teamId = ProjectionEventInput.positiveLong(payload.getValue("teamId"), "teamId")
        val occurredAt = ProjectionEventInput.occurredAt(payload)

        ProjectLifecycleRecordDecision.Apply(
            ProjectLifecycleEvent(
                projectId,
                teamId,
                eventType == "DELETED",
                occurredAt,
                payload.getValue("createdBy")?.let { ProjectionEventInput.positiveLong(it, "createdBy") },
                payload.getValue("isPrivate") as? Boolean ?: error("isPrivate must be a boolean"),
                payload.getJsonObject("initialReadGrant")?.let(InitialProjectReadGrant::fromJson),
            ),
        )
    }.getOrElse { error ->
        ProjectLifecycleRecordDecision.Quarantine(error.message ?: "invalid project lifecycle record")
    }

    private val EVENT_TYPES = setOf("CREATED", "UPDATED", "DELETED")
}
