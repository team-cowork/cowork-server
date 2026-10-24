package com.cowork.project.domain.project.service

import com.cowork.project.domain.membership.TeamRolePermissionReader
import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.project.entity.ProjectAccessProjection
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.repository.ProjectAccessProjectionRepository
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.global.projection.ProjectionReadinessGate
import java.time.Instant
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

    data class Permissions(val read: Boolean, val write: Boolean, val reorder: Boolean = false, val manage: Boolean = false)

    fun canRead(project: Project, userId: Long): Boolean = permissionsForUser(project, userId).read

    fun canWrite(project: Project, userId: Long): Boolean = permissionsForUser(project, userId).write

    private fun permissionsForUser(project: Project, userId: Long): Permissions {
        projectionReadinessGate.requireReady()
        val member = teamMembershipRepository.findActiveByTeamIdAndUserId(project.teamId, userId)
            ?: return Permissions(false, false)
        if (isManager(member.role)) return Permissions(true, true, true, true)
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
            project.id to (members[project.teamId]?.let { permissions(it, access[project.id]) } ?: Permissions(false, false))
        }
    }

    fun countReaders(project: Project): Long = projectRepository.countReadableUsers(project.id)

    private fun isManager(teamRole: String): Boolean = teamRole in setOf(TEAM_ROLE_OWNER, TEAM_ROLE_ADMIN)

    private fun permissions(member: TeamMembership, access: ProjectAccessProjection?): Permissions {
        if (isManager(member.role)) return Permissions(true, true, true, true)
        // TODO(#596): 2026-11-01 05:00 KST 이후 구형 레코드·롤백 지원 종료와 snapshot 보정 완료 확인 후 fallback 제거.
        val read = access?.canRead == true &&
            (access.sourceAuthorizationVersion ?: access.sourceMembershipVersion) == (member.authorizationVersion ?: member.sourceOccurredAt)
        return Permissions(read, read && access?.canWrite == true, read && access?.canReorder == true, read && access?.canManage == true)
    }

    fun requireProjectReader(project: Project, userId: Long) {
        if (!canRead(project, userId)) throw ExpectedException("프로젝트 읽기 권한이 없습니다.", HttpStatus.FORBIDDEN)
    }

    fun requireProjectModifier(project: Project, userId: Long) {
        if (!canWrite(project, userId)) throw ExpectedException("프로젝트 수정 권한이 없습니다.", HttpStatus.FORBIDDEN)
    }

    fun requireProjectManager(project: Project, userId: Long) {
        if (!permissionsForUser(project, userId).manage) {
            throw ExpectedException("프로젝트 관리 권한이 없습니다.", HttpStatus.FORBIDDEN)
        }
    }

    fun requireTeamProjectManager(teamId: Long, userId: Long) {
        requireTeamPermission(teamId, userId, "manage_projects")
    }

    data class InitialReadAuthorization(val membershipVersion: Instant, val authorizationVersion: Instant?)

    fun requireProjectCreation(teamId: Long, userId: Long, isPrivate: Boolean): InitialReadAuthorization? {
        val member = requireTeamPermission(teamId, userId, "manage_projects")
        if (!isPrivate || isManager(member.role)) return null
        if (!teamPermissions.hasPermission(teamId, userId, "project_read")) {
            throw ExpectedException("비공개 프로젝트 생성에는 프로젝트 읽기 권한이 필요합니다.", HttpStatus.FORBIDDEN)
        }
        if (!member.sourceOccurredAt.isAfter(Instant.EPOCH)) {
            throw ExpectedException("팀 멤버 권한 정보가 아직 준비되지 않았습니다.", HttpStatus.SERVICE_UNAVAILABLE)
        }
        // TODO(#596): 2026-11-01 05:00 KST 이후 구형 producer 중단·legacy seed 원장 소비·snapshot 보정 완료 확인 후 nullable 승인 제거.
        return InitialReadAuthorization(member.sourceOccurredAt, member.authorizationVersion)
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
        if (!isManager(member.role) && !teamPermissions.hasPermission(teamId, userId, permission)) {
            throw ExpectedException("팀 관리 권한이 없습니다.", HttpStatus.FORBIDDEN)
        }
        return member
    }
}
