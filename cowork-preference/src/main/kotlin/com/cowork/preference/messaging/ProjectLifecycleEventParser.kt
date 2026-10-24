package com.cowork.preference.messaging

import io.vertx.core.json.JsonObject
import java.time.Instant

/** project.event.v2에서 권한 판단에 필요한 소속 팀과 삭제 여부만 추린 것이다. */
data class ProjectLifecycleEvent(
    val projectId: Long,
    val teamId: Long,
    val deleted: Boolean,
    val occurredAt: Instant,
    val createdBy: Long? = null,
    val isPrivate: Boolean = true,
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
            ProjectLifecycleEvent(projectId, teamId, eventType == "DELETED", occurredAt,
                payload.getValue("createdBy")?.let { positiveLong(it, "createdBy") },
                // TODO(#596): 2026-11-01 05:00 KST 이후 아래 조건 완료를 확인하고 제거한다.
                // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
                // producer 전환·재동기화 및 구형 이벤트 재처리 종료 확인 후 누락 시 true 호환을 제거한다.
                if (payload.containsKey("isPrivate")) payload.getValue("isPrivate") as? Boolean ?: error("isPrivate must be a boolean") else true,
            ),
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
