package com.cowork.preference.service

import com.cowork.preference.messaging.ProjectionReadinessView
import com.cowork.preference.repository.ProjectLifecycleProjectionRepository
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.security.RequesterContext
import io.vertx.sqlclient.Pool

/**
 * 프로젝트 범위 Preference API의 접근 권한을 cowork-project의 `ProjectAccessGuard` 기준으로 검증한다.
 * 프로젝트 조회는 프로젝트가 속한 팀의 멤버, 수정·역할 관리는 그 팀의 OWNER/ADMIN에게 허용한다.
 * 소유 서비스는 프로젝트 OWNER·EDITOR 멤버에게도 수정을 허용하지만 `project.member.event.v2`에 프로젝트 역할이 없어
 * 이 서비스에서는 판단할 수 없으므로 허용하지 않는다. 소유 서비스와 같이 전역 ADMIN 우회는 없다.
 * [readiness]는 프로젝트 projection과 팀 멤버 projection이 모두 준비되었을 때만 열려야 한다.
 */
class ProjectAccessGuard(
    private val pool: Pool,
    private val projectRepository: ProjectLifecycleProjectionRepository,
    private val teamMemberRepository: TeamMemberProjectionRepository,
    private val readiness: ProjectionReadinessView,
) {
    /** 프로젝트 설정·역할 조회: 프로젝트가 속한 팀의 멤버. */
    suspend fun requireMember(projectId: Long, requester: RequesterContext) {
        activeTeamRole(projectId, requester.userId)
    }

    /** 프로젝트 설정 수정·역할 관리: 프로젝트가 속한 팀의 OWNER/ADMIN. */
    suspend fun requireManager(projectId: Long, requester: RequesterContext) {
        if (activeTeamRole(projectId, requester.userId) !in MANAGER_ROLES) {
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
