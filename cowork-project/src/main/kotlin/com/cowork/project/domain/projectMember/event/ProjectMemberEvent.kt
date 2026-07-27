package com.cowork.project.domain.projectMember.event

import java.time.LocalDateTime

enum class ProjectMemberEventType { ADDED, REMOVED }

data class ProjectMemberEvent(
    val eventType: ProjectMemberEventType,
    val projectId: Long,
    val userId: Long,
    val occurredAt: LocalDateTime = LocalDateTime.now(),
)
