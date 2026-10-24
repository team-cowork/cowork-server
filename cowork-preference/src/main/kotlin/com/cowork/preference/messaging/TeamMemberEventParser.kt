package com.cowork.preference.messaging

import io.vertx.core.json.JsonObject
import java.time.Instant

data class TeamMemberEvent(
    val eventType: String,
    val teamId: Long,
    val userId: Long,
    val role: String,
    val occurredAt: Instant,
    val authorizationVersion: Instant? = null,
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

        // TODO(#596): 2026-11-01 05:00 KST 이후 구형 producer/재처리 종료와 snapshot 보정 확인 후 누락 허용 제거.
        // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
        val authorizationVersion = payload.getValue("authorizationVersion")?.let { value ->
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

    private fun positiveLong(value: Any?, field: String): Long {
        val number = value as? Number ?: error("$field must be an integer")
        val parsed = number.toString().toLongOrNull() ?: error("$field must be an integer")
        require(parsed > 0) { "$field must be positive" }
        return parsed
    }

    private val EVENT_TYPES = setOf("UPSERT", "DELETE")
    private val ROLES = setOf("OWNER", "ADMIN", "MEMBER")
}
