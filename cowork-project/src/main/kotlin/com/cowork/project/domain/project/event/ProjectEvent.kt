package com.cowork.project.domain.project.event

import com.cowork.project.domain.project.entity.ProjectStatus

enum class ProjectEventType { CREATED, UPDATED, DELETED }

data class ProjectEvent(
    val eventType: ProjectEventType,
    val projectId: Long,
    val teamId: Long,
    val name: String,
    val description: String?,
    val status: ProjectStatus,
)
