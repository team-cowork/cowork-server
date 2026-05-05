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

@Service
@Transactional(readOnly = true)
class TeamRoleService(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val preferenceTeamRoleClient: PreferenceTeamRoleClient,
) {

    private fun requireTeam(teamId: Long) {
        if (!teamRepository.existsById(teamId)) {
            throw ExpectedException("팀을 찾을 수 없습니다. id=$teamId", HttpStatus.NOT_FOUND)
        }
    }

    private fun findMemberOrThrow(teamId: Long, userId: Long): TeamMember =
        teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
            ?: throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)

    private fun requireManageRoles(teamId: Long, userId: Long): TeamMember {
        val member = findMemberOrThrow(teamId, userId)
        if (member.role == TeamRole.OWNER || member.role == TeamRole.ADMIN) {
            return member
        }

        val roles = preferenceTeamRoleClient.getMemberRoles(teamId, userId)
        if (roles.any { "MANAGE_ROLES" in it.permissions }) {
            return member
        }

        throw ExpectedException("역할 관리 권한이 없습니다.", HttpStatus.FORBIDDEN)
    }

    private fun requireManageablePriority(teamId: Long, actor: TeamMember, role: TeamRoleResponse) {
        if (actor.role == TeamRole.OWNER || actor.role == TeamRole.ADMIN) {
            return
        }

        val actorMaxPriority = preferenceTeamRoleClient.getMemberRoles(teamId, actor.userId)
            .maxOfOrNull { it.priority } ?: 0
        if (role.priority >= actorMaxPriority) {
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
        if (actor.role != TeamRole.OWNER && actor.role != TeamRole.ADMIN) {
            val actorMaxPriority = preferenceTeamRoleClient.getMemberRoles(teamId, actorId)
                .maxOfOrNull { it.priority } ?: 0
            if (request.priority >= actorMaxPriority) {
                throw ExpectedException("자신의 최상위 역할보다 높거나 같은 역할은 만들 수 없습니다.", HttpStatus.FORBIDDEN)
            }
        }
        return preferenceTeamRoleClient.createRole(teamId, request)
    }

    fun updateRole(actorId: Long, teamId: Long, roleId: Long, request: UpdateTeamRoleRequest): TeamRoleResponse {
        val actor = requireManageRoles(teamId, actorId)
        val currentRole = getRoles(teamId).firstOrNull { it.id == roleId }
            ?: throw ExpectedException("역할을 찾을 수 없습니다. id=$roleId", HttpStatus.NOT_FOUND)
        requireManageablePriority(teamId, actor, currentRole)
        request.priority?.let {
            requireManageablePriority(teamId, actor, currentRole.copy(priority = it))
        }
        return preferenceTeamRoleClient.updateRole(teamId, roleId, request)
    }

    fun deleteRole(actorId: Long, teamId: Long, roleId: Long) {
        val actor = requireManageRoles(teamId, actorId)
        val role = getRoles(teamId).firstOrNull { it.id == roleId }
            ?: throw ExpectedException("역할을 찾을 수 없습니다. id=$roleId", HttpStatus.NOT_FOUND)
        requireManageablePriority(teamId, actor, role)
        preferenceTeamRoleClient.deleteRole(teamId, roleId)
    }

    fun assignRole(actorId: Long, teamId: Long, targetUserId: Long, roleId: Long): TeamRoleResponse {
        val actor = requireManageRoles(teamId, actorId)
        requireMember(teamId, targetUserId)
        val role = getRoles(teamId).firstOrNull { it.id == roleId }
            ?: throw ExpectedException("역할을 찾을 수 없습니다. id=$roleId", HttpStatus.NOT_FOUND)
        requireManageablePriority(teamId, actor, role)
        return preferenceTeamRoleClient.assignRole(teamId, targetUserId, roleId)
    }

    fun revokeRole(actorId: Long, teamId: Long, targetUserId: Long, roleId: Long) {
        val actor = requireManageRoles(teamId, actorId)
        requireMember(teamId, targetUserId)
        val role = getRoles(teamId).firstOrNull { it.id == roleId }
            ?: throw ExpectedException("역할을 찾을 수 없습니다. id=$roleId", HttpStatus.NOT_FOUND)
        requireManageablePriority(teamId, actor, role)
        preferenceTeamRoleClient.revokeRole(teamId, targetUserId, roleId)
    }
}
