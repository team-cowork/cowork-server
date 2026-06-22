package com.cowork.project.service

import com.cowork.project.domain.Project
import com.cowork.project.domain.ProjectMemberRole
import com.cowork.project.repository.ProjectMemberRepository
import com.cowork.project.repository.ProjectRepository
import com.cowork.project.repository.TeamMembershipRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

private const val TEAM_ROLE_OWNER = "OWNER"
private const val TEAM_ROLE_ADMIN = "ADMIN"

/**
 * 프로젝트 조회/수정 권한 검증을 담당하는 컴포넌트.
 *
 * `ProjectService`와 `GithubPullRequestService`가 동일한 권한 판단 기준을 공유하기 위해
 * 분리되었다.
 */
@Component
class ProjectAccessGuard(
    private val projectRepository: ProjectRepository,
    private val projectMemberRepository: ProjectMemberRepository,
    private val teamMembershipRepository: TeamMembershipRepository,
) {

    fun findProjectOrThrow(projectId: Long): Project =
        projectRepository.findById(projectId).orElseThrow {
            ExpectedException("프로젝트를 찾을 수 없습니다. id=$projectId", HttpStatus.NOT_FOUND)
        }

    fun teamRoleOf(teamId: Long, userId: Long): String? =
        teamMembershipRepository.findByTeamIdAndUserId(teamId, userId)?.role

    fun requireTeamMember(teamId: Long, userId: Long) {
        teamRoleOf(teamId, userId)
            ?: throw ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)
    }

    private fun isTeamOwnerOrAdmin(teamId: Long, userId: Long): Boolean =
        teamRoleOf(teamId, userId) in setOf(TEAM_ROLE_OWNER, TEAM_ROLE_ADMIN)

    fun requireProjectModifier(project: Project, userId: Long) {
        val role = projectMemberRepository.findByProjectIdAndUserId(project.id, userId)?.role
        if (role == ProjectMemberRole.OWNER || role == ProjectMemberRole.EDITOR) return
        if (isTeamOwnerOrAdmin(project.teamId, userId)) return
        throw ExpectedException("프로젝트 수정 권한이 없습니다.", HttpStatus.FORBIDDEN)
    }

    fun requireProjectOwner(project: Project, userId: Long) {
        val role = projectMemberRepository.findByProjectIdAndUserId(project.id, userId)?.role
        if (role == ProjectMemberRole.OWNER) return
        if (isTeamOwnerOrAdmin(project.teamId, userId)) return
        throw ExpectedException("해당 작업은 프로젝트 OWNER만 수행할 수 있습니다.", HttpStatus.FORBIDDEN)
    }
}
