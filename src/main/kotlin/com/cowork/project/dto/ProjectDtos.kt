package com.cowork.project.dto

import com.cowork.project.domain.Project
import com.cowork.project.domain.ProjectMember
import com.cowork.project.domain.ProjectMemberRole
import com.cowork.project.domain.ProjectStatus
import com.cowork.user.dto.UserSummaryResponse
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class CreateProjectRequest(
    @field:Schema(description = "프로젝트를 만들 팀 ID", example = "1")
    @field:NotNull
    val teamId: Long,
    @field:Schema(description = "프로젝트 이름", example = "cowork 앱")
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,
    @field:Size(max = 500)
    val description: String? = null,
    @field:Schema(description = "GitHub 저장소 주소", example = "https://github.com/team-cowork/cowork-app")
    @field:Size(max = 512)
    @field:Pattern(regexp = GITHUB_REPO_PATTERN)
    val githubRepoUrl: String? = null,
)

data class UpdateProjectRequest(
    @field:Size(min = 1, max = 100)
    val name: String? = null,
    @field:Size(max = 500)
    val description: String? = null,
    @field:Size(max = 512)
    @field:Pattern(regexp = GITHUB_REPO_PATTERN)
    val githubRepoUrl: String? = null,
    val status: ProjectStatus? = null,
)

data class ReorderProjectsRequest(
    @field:NotEmpty
    val projectIds: List<Long>,
)

data class AddProjectMemberRequest(
    @field:NotNull
    val userId: Long,
    val role: ProjectMemberRole = ProjectMemberRole.EDITOR,
)

data class ChangeProjectMemberRoleRequest(
    @field:NotNull
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
