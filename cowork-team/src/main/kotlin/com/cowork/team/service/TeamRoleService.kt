package com.cowork.team.service

import com.cowork.team.client.PreferenceTeamRoleClient
import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamRole
import com.cowork.team.dto.CreateTeamRoleRequest
import com.cowork.team.dto.TeamRoleResponse
import com.cowork.team.dto.UpdateTeamRoleRequest
import com.cowork.team.repository.TeamMemberRepository
import com.cowork.team.repository.TeamRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

private const val MANAGE_ROLES_PERMISSION = "MANAGE_ROLES"

@Service
@Transactional(readOnly = true)
class TeamRoleService(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val preferenceTeamRoleClient: PreferenceTeamRoleClient,
) {

    private data class ManageRoleContext(
        val member: TeamMember,
        val roles: List<TeamRoleResponse>,
    ) {
        val maxPriority: Int = roles.maxOfOrNull { it.priority } ?: 0
    }

    private fun requireTeam(teamId: Long) {
        if (!teamRepository.existsById(teamId)) {
            throw ExpectedException("팀을 찾을 수 없습니다. id=$teamId", HttpStatus.NOT_FOUND)
        }
    }

    private fun findMemberOrThrow(teamId: Long, userId: Long): TeamMember =
        teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
            ?: throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)

    private fun requireManageRoles(teamId: Long, userId: Long): ManageRoleContext {
        val member = findMemberOrThrow(teamId, userId)
        if (member.role == TeamRole.OWNER || member.role == TeamRole.ADMIN) {
            return ManageRoleContext(member, emptyList())
        }

        val roles = preferenceTeamRoleClient.getMemberRoles(teamId, userId)
        if (roles.any { MANAGE_ROLES_PERMISSION in it.permissions }) {
            return ManageRoleContext(member, roles)
        }

        throw ExpectedException("역할 관리 권한이 없습니다.", HttpStatus.FORBIDDEN)
    }

    private fun requireManageablePriority(actor: ManageRoleContext, role: TeamRoleResponse) {
        if (actor.member.role == TeamRole.OWNER || actor.member.role == TeamRole.ADMIN) {
            return
        }

        if (role.priority >= actor.maxPriority) {
            throw ExpectedException("자신의 최상위 역할보다 높거나 같은 역할은 관리할 수 없습니다.", HttpStatus.FORBIDDEN)
        }
    }

    private fun requireMember(teamId: Long, userId: Long) {
        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw ExpectedException("해당 멤버를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
    }

    fun getRoles(teamId: Long): List<TeamRoleResponse> {
        requireTeam(teamId)
        return preferenceTeamRoleClient.getRoles(teamId)
    }

    fun getMemberRoles(teamId: Long, userId: Long): List<TeamRoleResponse> {
        requireMember(teamId, userId)
        return preferenceTeamRoleClient.getMemberRoles(teamId, userId)
    }

    fun createRole(actorId: Long, teamId: Long, request: CreateTeamRoleRequest): TeamRoleResponse {
        val actor = requireManageRoles(teamId, actorId)
        if (actor.member.role != TeamRole.OWNER && actor.member.role != TeamRole.ADMIN) {
            if (request.priority >= actor.maxPriority) {
                throw ExpectedException("자신의 최상위 역할보다 높거나 같은 역할은 만들 수 없습니다.", HttpStatus.FORBIDDEN)
            }
        }
        return preferenceTeamRoleClient.createRole(teamId, request)
    }

    fun updateRole(actorId: Long, teamId: Long, roleId: Long, request: UpdateTeamRoleRequest): TeamRoleResponse {
        val actor = requireManageRoles(teamId, actorId)
        val allRoles = getRoles(teamId)
        val currentRole = allRoles.firstOrNull { it.id == roleId }
            ?: throw ExpectedException("역할을 찾을 수 없습니다. id=$roleId", HttpStatus.NOT_FOUND)
        requireManageablePriority(actor, currentRole)
        request.priority?.let {
            requireManageablePriority(actor, currentRole.copy(priority = it))
        }
        return preferenceTeamRoleClient.updateRole(teamId, roleId, request)
    }

    fun deleteRole(actorId: Long, teamId: Long, roleId: Long) {
        val actor = requireManageRoles(teamId, actorId)
        val role = getRoles(teamId).firstOrNull { it.id == roleId }
            ?: throw ExpectedException("역할을 찾을 수 없습니다. id=$roleId", HttpStatus.NOT_FOUND)
        requireManageablePriority(actor, role)
        preferenceTeamRoleClient.deleteRole(teamId, roleId)
    }

    fun assignRole(actorId: Long, teamId: Long, targetUserId: Long, roleId: Long): TeamRoleResponse {
        val actor = requireManageRoles(teamId, actorId)
        requireMember(teamId, targetUserId)
        val role = getRoles(teamId).firstOrNull { it.id == roleId }
            ?: throw ExpectedException("역할을 찾을 수 없습니다. id=$roleId", HttpStatus.NOT_FOUND)
        requireManageablePriority(actor, role)
        return preferenceTeamRoleClient.assignRole(teamId, roleId, mapOf("accountId" to targetUserId))
    }

    fun revokeRole(actorId: Long, teamId: Long, targetUserId: Long, roleId: Long) {
        val actor = requireManageRoles(teamId, actorId)
        requireMember(teamId, targetUserId)
        val role = getRoles(teamId).firstOrNull { it.id == roleId }
            ?: throw ExpectedException("역할을 찾을 수 없습니다. id=$roleId", HttpStatus.NOT_FOUND)
        requireManageablePriority(actor, role)
        preferenceTeamRoleClient.revokeRole(teamId, targetUserId, roleId)
    }
}
