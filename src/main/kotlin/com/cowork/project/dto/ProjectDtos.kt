package com.cowork.project.dto

import com.cowork.project.domain.Project
import com.cowork.project.domain.ProjectMember
import com.cowork.project.domain.ProjectMemberRole
import com.cowork.project.domain.ProjectStatus
import com.cowork.user.dto.UserSummaryResponse
import java.time.LocalDateTime

data class CreateProjectRequest(
    val teamId: Long,
    val name: String,
    val description: String? = null,
    val githubRepoUrl: String? = null,
)

data class UpdateProjectRequest(
    val name: String? = null,
    val description: String? = null,
    val githubRepoUrl: String? = null,
    val status: ProjectStatus? = null,
)

data class ReorderProjectsRequest(
    val projectIds: List<Long>,
)

data class AddProjectMemberRequest(
    val userId: Long,
    val role: ProjectMemberRole = ProjectMemberRole.EDITOR,
)

data class ChangeProjectMemberRoleRequest(
    val role: ProjectMemberRole,
)

data class ProjectResponse(
    val id: Long,
    val teamId: Long,
    val name: String,
    val description: String?,
    val githubRepoUrl: String?,
    val status: ProjectStatus,
    val position: Int,
    val createdBy: Long,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(project: Project) = ProjectResponse(
            id = project.id,
            teamId = project.teamId,
            name = project.name,
            description = project.description,
            githubRepoUrl = project.githubRepoUrl,
            status = project.status,
            position = project.position,
            createdBy = project.createdBy,
            createdAt = project.createdAt,
        )
    }
}

data class ProjectMemberResponse(
    val userId: Long,
    val role: ProjectMemberRole,
    val joinedAt: LocalDateTime,
    val user: UserSummaryResponse?,
) {
    companion object {
        fun of(member: ProjectMember, user: UserSummaryResponse?) = ProjectMemberResponse(
            userId = member.userId,
            role = member.role,
            joinedAt = member.joinedAt,
            user = user,
        )
    }
}

private const val GITHUB_REPO_PATTERN = "^$|^https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/?$"
