package com.cowork.team.domain.teamRole.service

import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionReader
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.themoment.sdk.exception.ExpectedException

private const val MANAGE_ROLES_PERMISSION = "MANAGE_ROLES"

data class ManageRoleContext(val member: TeamMember, val roles: List<TeamRoleResponse>) {
    val maxPriority: Int = roles.maxOfOrNull { it.priority } ?: 0
}

@Service
class TeamRoleAccessGuard(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val teamRoleProjectionReader: TeamRoleProjectionReader,
) {

    fun requireTeam(teamId: Long) {
        if (!teamRepository.existsById(teamId)) {
            throw ExpectedException("팀을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
    }

    fun findMemberOrThrow(teamId: Long, userId: Long): TeamMember =
        teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
            ?: throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)

    fun requireMemberExists(teamId: Long, userId: Long) {
        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw ExpectedException("해당 멤버를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
    }

    fun requireManageRoles(teamId: Long, userId: Long): ManageRoleContext {
        val member = findMemberOrThrow(teamId, userId)
        if (member.role == TeamRole.OWNER || member.role == TeamRole.ADMIN) {
            return ManageRoleContext(member, emptyList())
        }

        val roles = teamRoleProjectionReader.getMemberRoles(teamId, userId)
        if (roles.any { MANAGE_ROLES_PERMISSION in it.permissions }) {
            return ManageRoleContext(member, roles)
        }

        throw ExpectedException("역할 관리 권한이 없습니다.", HttpStatus.FORBIDDEN)
    }

    fun requireManageablePriority(actor: ManageRoleContext, role: TeamRoleResponse) {
        if (actor.member.role == TeamRole.OWNER || actor.member.role == TeamRole.ADMIN) {
            return
        }

        if (role.priority >= actor.maxPriority) {
            throw ExpectedException("자신의 최상위 역할보다 높거나 같은 역할은 관리할 수 없습니다.", HttpStatus.FORBIDDEN)
        }
    }
}
