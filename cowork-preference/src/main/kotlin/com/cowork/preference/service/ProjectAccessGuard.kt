package com.cowork.preference.service

import com.cowork.preference.messaging.ProjectionReadinessView
import com.cowork.preference.repository.ProjectLifecycleProjectionRepository
import com.cowork.preference.repository.ProjectRoleRepository
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.repository.TeamRoleRepository
import com.cowork.preference.security.RequesterContext
import io.vertx.sqlclient.Pool
import io.vertx.core.json.JsonObject

/** 프로젝트 조회와 설정 관리는 각각 유효한 read와 manage 권한으로 판단한다. */
class ProjectAccessGuard(
    private val pool: Pool,
    private val projectRepository: ProjectLifecycleProjectionRepository,
    private val teamMemberRepository: TeamMemberProjectionRepository,
    private val readiness: ProjectionReadinessView,
    private val roles: ProjectRoleRepository,
    private val teamRoles: TeamRoleRepository,
) {
    suspend fun requireTeamMember(projectId: Long, accountId: Long) {
        activeTeamRole(projectId, accountId)
    }

    /** 프로젝트 설정·역할 조회: read 권한 보유자 또는 프로젝트 관리자. */
    suspend fun requireMember(projectId: Long, requester: RequesterContext) {
        val role = activeTeamRole(projectId, requester.userId)
        if (role in MANAGER_ROLES) return
        val allowed = roles.canRead(projectId, requester.userId)
        if (!allowed) throw ResourceAccessDeniedException("프로젝트 읽기 권한이 없습니다.")
    }

    /** 프로젝트 설정 수정: manage 권한 보유자 또는 팀 OWNER/ADMIN. */
    suspend fun requireManager(projectId: Long, requester: RequesterContext) {
        if (activeTeamRole(projectId, requester.userId) !in MANAGER_ROLES &&
            !roles.canManage(projectId, requester.userId)) {
            throw ResourceAccessDeniedException("프로젝트 설정을 관리할 권한이 없습니다.")
        }
    }

    suspend fun requireRoleCreation(projectId: Long, requester: RequesterContext, permissions: JsonObject) {
        requireRoleMutation(projectId, requester, permissions, removing = false)
    }

    suspend fun requireRoleAssignment(projectId: Long, requester: RequesterContext, roleName: String) {
        val permissions = roles.findRole(projectId, roleName)?.permissions ?: JsonObject()
        requireRoleMutation(projectId, requester, permissions, removing = false)
    }

    suspend fun requireRoleRemoval(projectId: Long, requester: RequesterContext, roleName: String) {
        val permissions = roles.findRole(projectId, roleName)?.permissions ?: JsonObject()
        requireRoleMutation(projectId, requester, permissions, removing = true)
    }

    private suspend fun requireRoleMutation(projectId: Long, requester: RequesterContext, permissions: JsonObject, removing: Boolean) {
        if (activeTeamRole(projectId, requester.userId) in MANAGER_ROLES) return
        val project = requireNotNull(projectRepository.findForRead(pool, projectId))
        val effective = roles.effectivePermissions(projectId, requester.userId)
        if (!effective.read || !teamRoles.hasPermission(project.teamId, requester.userId, "manage_roles")) {
            throw ResourceAccessDeniedException("프로젝트 역할을 관리할 권한이 없습니다.")
        }
        // Removing a read deny can restore additional inherited permissions on the target.
        if (removing && permissions.map.values.any { it == false }) {
            throw ResourceAccessDeniedException("거부 정책을 제거하려면 팀 관리자 권한이 필요합니다.")
        }
        val held = mapOf("read" to effective.read, "write" to effective.write, "manage" to effective.manage, "reorder" to effective.reorder)
        if (!removing && permissions.map.any { (key, value) -> value == true && held[key] != true }) {
            throw ResourceAccessDeniedException("보유하지 않은 프로젝트 권한은 부여할 수 없습니다.")
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
