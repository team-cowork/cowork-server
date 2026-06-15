package com.cowork.project.event

data class ProjectEvent(
    val eventType: String,
    val projectId: Long,
    val teamId: Long,
    val name: String,
    val description: String?,
    val status: String,
)
