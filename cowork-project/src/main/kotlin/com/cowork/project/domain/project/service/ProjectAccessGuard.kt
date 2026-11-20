package com.cowork.project.domain.project.service

import com.cowork.project.domain.membership.TeamRolePermissionReader
import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.entity.ProjectAccessProjection
import com.cowork.project.domain.project.repository.ProjectAccessProjectionRepository
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.global.projection.ProjectionReadinessGate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant

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
    private val teamPermissions: TeamRolePermissionReader,
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

    data class Permissions(
        val read: Boolean,
        val write: Boolean,
        val reorder: Boolean = false,
        val manage: Boolean = false,
        val boardRead: Boolean = false,
        val boardWrite: Boolean = false,
    )

    fun canRead(project: Project, userId: Long): Boolean = permissionsForUser(project, userId).read

    fun canWrite(project: Project, userId: Long): Boolean = permissionsForUser(project, userId).write

    private fun permissionsForUser(project: Project, userId: Long): Permissions {
        projectionReadinessGate.requireReady()
        val member = teamMembershipRepository.findActiveByTeamIdAndUserId(project.teamId, userId)
            ?: return Permissions(false, false)
        return permissions(member, accessRepository.findById("${project.id}:$userId").orElse(null))
    }

    fun permissionsForUsers(project: Project, memberships: List<TeamMembership>): Map<Long, Permissions> {
        projectionReadinessGate.requireReady()
        val access = accessRepository.findAllByProjectId(project.id).associateBy { it.userId }
        return memberships.filter { it.active && it.teamId == project.teamId }.associate { member ->
            member.userId to permissions(member, access[member.userId])
        }
    }

    fun permissionsForProjects(projects: List<Project>, userId: Long): Map<Long, Permissions> {
        projectionReadinessGate.requireReady()
        if (projects.isEmpty()) return emptyMap()
        val access = accessRepository.findAllByUserIdAndProjectIdIn(userId, projects.map { it.id })
            .associateBy { it.projectId }
        val members = projects.map { it.teamId }.distinct().associateWith {
            teamMembershipRepository.findActiveByTeamIdAndUserId(it, userId)
        }
        return projects.associate { project ->
            project.id to
                (members[project.teamId]?.let { permissions(it, access[project.id]) } ?: Permissions(false, false))
        }
    }

    fun countReaders(project: Project): Long = projectRepository.countReadableUsers(project.id)

    private fun permissions(member: TeamMembership, access: ProjectAccessProjection?): Permissions {
        val read = access?.canRead == true && hasCurrentAuthorization(member, access)
        val boardRead = read && access?.canBoardRead == true
        return Permissions(
            read,
            read && access?.canWrite == true,
            read && access?.canReorder == true,
            read && access?.canManage == true,
            boardRead,
            boardRead && access?.canBoardWrite == true,
        )
    }

    private fun hasCurrentAuthorization(member: TeamMembership, access: ProjectAccessProjection): Boolean =
        member.authorizationVersion != null && access.sourceAuthorizationVersion == member.authorizationVersion

    fun requireProjectReader(project: Project, userId: Long) {
        requireAccess(project, userId, "프로젝트 읽기 권한이 없습니다.") { it.read }
    }

    fun requireProjectModifier(project: Project, userId: Long) {
        requireAccess(project, userId, "프로젝트 수정 권한이 없습니다.") { it.write }
    }

    fun requireBoardReader(project: Project, userId: Long) {
        requireAccess(project, userId, "GitHub 보드 보기 권한이 없습니다.") { it.boardRead }
    }

    fun requireBoardModifier(project: Project, userId: Long) {
        requireAccess(project, userId, "GitHub 보드 수정 권한이 없습니다.") { it.boardWrite }
    }

    fun requireProjectManager(project: Project, userId: Long) {
        requireAccess(project, userId, "프로젝트 관리 권한이 없습니다.") { it.manage }
    }

    /**
     * preference는 프로젝트 권한을 다시 계산할 때 팀 멤버마다 권한 행을 발행한다. 따라서 활성 멤버의 권한 행이 없거나
     * 현재 권한 버전과 다르면 거부가 아니라 전파 지연이다. 프로젝트 생성이나 역할 변경 직후의 요청이 이 경우에 해당하며,
     * 권한을 미리 주지 않고 재시도할 수 있는 503으로 구분한다.
     */
    private fun requireAccess(project: Project, userId: Long, denial: String, granted: (Permissions) -> Boolean) {
        projectionReadinessGate.requireReady()
        val member = teamMembershipRepository.findActiveByTeamIdAndUserId(project.teamId, userId)
            ?: throw ExpectedException(denial, HttpStatus.FORBIDDEN)
        val access = accessRepository.findById("${project.id}:$userId").orElse(null)
        if (granted(permissions(member, access))) return
        if (access == null || !hasCurrentAuthorization(member, access)) {
            throw ExpectedException(ACCESS_SYNC_PENDING_MESSAGE, HttpStatus.SERVICE_UNAVAILABLE)
        }
        throw ExpectedException(denial, HttpStatus.FORBIDDEN)
    }

    fun requirePrivateTransitionAccess(project: Project, userId: Long) {
        projectionReadinessGate.requireReady()
        val member = teamMembershipRepository.findActiveByTeamIdAndUserId(project.teamId, userId)
            ?: throw ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)
        if (teamPermissions.hasPermission(member.teamId, member.userId, "administrator")) return
        val access = accessRepository.findById("${project.id}:$userId").orElse(null)
        if (access?.canReadPrivate != true || !hasCurrentAuthorization(member, access)) {
            throw ExpectedException("비공개 전환 후에도 접근할 수 있도록 프로젝트 읽기 역할을 설정해야 합니다.", HttpStatus.BAD_REQUEST)
        }
    }

    fun requireTeamProjectManager(teamId: Long, userId: Long) {
        requireTeamPermission(teamId, userId, "manage_projects")
    }

    data class InitialReadAuthorization(val membershipVersion: Instant, val authorizationVersion: Instant)

    fun requireProjectCreation(teamId: Long, userId: Long, isPrivate: Boolean): InitialReadAuthorization? {
        val member = requireTeamPermission(teamId, userId, "manage_projects")
        if (!isPrivate || teamPermissions.hasPermission(member.teamId, member.userId, "administrator")) return null
        if (!teamPermissions.hasPermission(teamId, userId, "project_read")) {
            throw ExpectedException("비공개 프로젝트 생성에는 프로젝트 읽기 권한이 필요합니다.", HttpStatus.FORBIDDEN)
        }
        val authorizationVersion = member.authorizationVersion
        if (!member.sourceOccurredAt.isAfter(Instant.EPOCH) || authorizationVersion == null) {
            throw ExpectedException("팀 멤버 권한 정보가 아직 준비되지 않았습니다.", HttpStatus.SERVICE_UNAVAILABLE)
        }
        return InitialReadAuthorization(member.sourceOccurredAt, authorizationVersion)
    }

    fun requireProjectOwner(project: Project, userId: Long) {
        requireProjectReader(project, userId)
        requireTeamProjectManager(project.teamId, userId)
    }

    fun requireGithubManager(project: Project, userId: Long) {
        requireProjectReader(project, userId)
        requireTeamPermission(project.teamId, userId, "manage_github")
    }

    private fun requireTeamPermission(teamId: Long, userId: Long, permission: String): TeamMembership {
        projectionReadinessGate.requireReady()
        val member = teamMembershipRepository.findActiveByTeamIdAndUserId(teamId, userId)
            ?: throw ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)
        if (!teamPermissions.hasPermission(teamId, userId, permission)) {
            throw ExpectedException("팀 관리 권한이 없습니다.", HttpStatus.FORBIDDEN)
        }
        return member
    }

    private companion object {
        const val ACCESS_SYNC_PENDING_MESSAGE = "프로젝트 권한 정보를 동기화하고 있습니다. 잠시 후 다시 시도해 주세요."
    }
}
