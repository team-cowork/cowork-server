package com.cowork.project.domain.projectMember.event

import java.time.Instant

enum class ProjectMemberEventType { ADDED, REMOVED }

data class ProjectMemberEvent(
    val eventType: ProjectMemberEventType,
    val projectId: Long,
    val userId: Long,
    val occurredAt: Instant = Instant.now(),
    val snapshot: Boolean = false,
)
