package com.cowork.project.dto

import com.cowork.project.domain.ProjectMember
import java.time.LocalDateTime

data class ProjectMemberResponse(
    val id: Long,
    val projectId: Long,
    val userId: Long,
    val role: String,
    val joinedAt: LocalDateTime,
) {
    companion object {
        fun of(member: ProjectMember): ProjectMemberResponse =
            ProjectMemberResponse(
                id = member.id,
                projectId = member.projectId,
                userId = member.userId,
                role = member.role.name,
                joinedAt = member.joinedAt,
            )
    }
}
