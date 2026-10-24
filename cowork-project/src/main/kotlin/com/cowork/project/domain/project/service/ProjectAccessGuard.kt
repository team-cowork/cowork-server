package com.cowork.project.domain.project.service

import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.project.entity.ProjectAccessProjection
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.repository.ProjectAccessProjectionRepository
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.global.projection.ProjectionReadinessGate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.themoment.sdk.exception.ExpectedException

private const val TEAM_ROLE_OWNER = "OWNER"
private const val TEAM_ROLE_ADMIN = "ADMIN"

/**
 * 프로젝트 조회/수정 권한 검증을 담당하는 컴포넌트.
 *
 * project 도메인의 엔드포인트별 서비스들과 `GithubRepoAccessResolver`가 동일한 권한 판단 기준을
 * 공유하기 위해 분리되었다.
 */
@Service
class ProjectAccessGuard(
    private val projectRepository: ProjectRepository,
    private val accessRepository: ProjectAccessProjectionRepository,
    private val teamMembershipRepository: TeamMembershipRepository,
    private val projectionReadinessGate: ProjectionReadinessGate,
) {

    fun findProjectOrThrow(projectId: Long): Project = projectRepository.findById(projectId).orElseThrow {
        ExpectedException("프로젝트를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
    }

    fun findProjectForUpdateOrThrow(projectId: Long): Project = projectRepository.findByIdForUpdate(projectId)
        ?: throw ExpectedException("프로젝트를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)

    fun teamRoleOf(teamId: Long, userId: Long): String? {
        projectionReadinessGate.requireReady()
        return teamMembershipRepository.findActiveByTeamIdAndUserId(teamId, userId)?.role
    }

    fun requireTeamMember(teamId: Long, userId: Long): String = teamRoleOf(teamId, userId)
        ?: throw ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

    private fun isTeamOwnerOrAdmin(teamId: Long, userId: Long): Boolean =
        teamRoleOf(teamId, userId) in setOf(TEAM_ROLE_OWNER, TEAM_ROLE_ADMIN)

    data class Permissions(val read: Boolean, val write: Boolean)

    fun canRead(project: Project, userId: Long): Boolean = permissionsForUser(project, userId).read

    fun canWrite(project: Project, userId: Long): Boolean = permissionsForUser(project, userId).write

    private fun permissionsForUser(project: Project, userId: Long): Permissions {
        val role = teamRoleOf(project.teamId, userId) ?: return Permissions(false, false)
        if (isManager(project, userId, role)) return Permissions(true, true)
        return permissions(project, userId, role, accessRepository.findById("${project.id}:$userId").orElse(null))
    }

    fun permissionsForUsers(project: Project, memberships: List<TeamMembership>): Map<Long, Permissions> {
        projectionReadinessGate.requireReady()
        val access = accessRepository.findAllByProjectId(project.id).associateBy { it.userId }
        return memberships.filter { it.active && it.teamId == project.teamId }.associate { member ->
            member.userId to permissions(project, member.userId, member.role, access[member.userId])
        }
    }

    fun permissionsForProjects(projects: List<Project>, userId: Long, teamRole: String): Map<Long, Permissions> {
        if (projects.isEmpty()) return emptyMap()
        val access = accessRepository.findAllByUserIdAndProjectIdIn(userId, projects.map { it.id })
            .associateBy { it.projectId }
        return projects.associate { project -> project.id to permissions(project, userId, teamRole, access[project.id]) }
    }

    fun countReaders(project: Project): Long = projectRepository.countReadableUsers(project.id)

    private fun isManager(project: Project, userId: Long, teamRole: String): Boolean =
        project.createdBy == userId || teamRole in setOf(TEAM_ROLE_OWNER, TEAM_ROLE_ADMIN)

    private fun permissions(project: Project, userId: Long, role: String, access: ProjectAccessProjection?): Permissions {
        if (isManager(project, userId, role)) return Permissions(true, true)
        val read = access?.canRead == true
        return Permissions(read, read && access?.canWrite == true)
    }

    fun requireProjectReader(project: Project, userId: Long) {
        if (!canRead(project, userId)) throw ExpectedException("프로젝트 읽기 권한이 없습니다.", HttpStatus.FORBIDDEN)
    }

    fun requireProjectModifier(project: Project, userId: Long) {
        if (!canWrite(project, userId)) throw ExpectedException("프로젝트 수정 권한이 없습니다.", HttpStatus.FORBIDDEN)
    }

    fun requireProjectOwner(project: Project, userId: Long) {
        requireTeamMember(project.teamId, userId)
        if (project.createdBy == userId || isTeamOwnerOrAdmin(project.teamId, userId)) return
        throw ExpectedException("프로젝트 관리 권한이 없습니다.", HttpStatus.FORBIDDEN)
    }
}
