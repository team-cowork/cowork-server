package com.cowork.project.dto

import com.cowork.project.domain.Project
import java.time.LocalDateTime

data class ProjectDetailResponse(
    val id: Long,
    val teamId: Long,
    val name: String,
    val description: String?,
    val status: String,
    val createdBy: Long,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val memberCount: Long,
) {
    companion object {
        fun of(project: Project, memberCount: Long): ProjectDetailResponse =
            ProjectDetailResponse(
                id = project.id,
                teamId = project.teamId,
                name = project.name,
                description = project.description,
                status = project.status.name,
                createdBy = project.createdBy,
                createdAt = project.createdAt,
                updatedAt = project.updatedAt,
                memberCount = memberCount,
            )
    }
}
