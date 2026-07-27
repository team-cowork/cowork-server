package com.cowork.project.domain.projectMember.event

import java.time.LocalDateTime

data class ProjectMemberEvent(
    val eventType: String,
    val projectId: Long,
    val userId: Long,
    val occurredAt: LocalDateTime = LocalDateTime.now(),
)
