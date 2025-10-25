package com.cowork.project.event

data class ProjectDeletedEvent(
    val projectId: Long,
    val teamId: Long,
)

data class ProjectMemberAddedEvent(
    val projectId: Long,
    val projectName: String,
    val userId: Long,
    val addedBy: Long,
)
