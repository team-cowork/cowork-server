package com.cowork.preference.service

import com.cowork.preference.messaging.ProjectionReadinessView
import com.cowork.preference.repository.ProjectLifecycleProjectionRepository
import com.cowork.preference.repository.ProjectRoleRepository
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.security.RequesterContext
import io.vertx.sqlclient.Pool

/** 프로젝트 조회는 read, 설정·역할 관리는 생성자 또는 팀 관리 권한으로 판단한다. */
class ProjectAccessGuard(
    private val pool: Pool,
    private val projectRepository: ProjectLifecycleProjectionRepository,
    private val teamMemberRepository: TeamMemberProjectionRepository,
    private val readiness: ProjectionReadinessView,
    private val roles: ProjectRoleRepository,
) {
    suspend fun requireTeamMember(projectId: Long, accountId: Long) {
        activeTeamRole(projectId, accountId)
    }

    /** 프로젝트 설정·역할 조회: read 권한 보유자 또는 프로젝트 관리자. */
    suspend fun requireMember(projectId: Long, requester: RequesterContext) {
        val role = activeTeamRole(projectId, requester.userId)
        val project = projectRepository.findForRead(pool, projectId)
        if (role in MANAGER_ROLES || project?.createdBy == requester.userId) return
        val allowed = roles.canRead(projectId, requester.userId)
        if (!allowed) throw ResourceAccessDeniedException("프로젝트 읽기 권한이 없습니다.")
    }

    /** 프로젝트 설정 수정·역할 관리: 생성자 또는 팀 OWNER/ADMIN. */
    suspend fun requireManager(projectId: Long, requester: RequesterContext) {
        if (activeTeamRole(projectId, requester.userId) !in MANAGER_ROLES &&
            projectRepository.findForRead(pool, projectId)?.createdBy != requester.userId) {
            throw ResourceAccessDeniedException("프로젝트 설정을 관리할 권한이 없습니다.")
        }
    }

    private suspend fun activeTeamRole(projectId: Long, accountId: Long): String {
        if (!readiness.isReady) {
            throw ProjectionNotReadyException("project lifecycle or team member projection is not ready")
        }
        val project = projectRepository.findForRead(pool, projectId)?.takeUnless { it.deleted }
            ?: throw ResourceAccessDeniedException("프로젝트 팀 멤버만 접근할 수 있습니다.")
        val member = teamMemberRepository.findForRead(pool, project.teamId, accountId)
        if (member == null || member.deleted) {
            throw ResourceAccessDeniedException("프로젝트 팀 멤버만 접근할 수 있습니다.")
        }
        return member.builtInRole
    }

    private companion object {
        val MANAGER_ROLES = setOf("OWNER", "ADMIN")
    }
}
