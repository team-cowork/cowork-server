package com.cowork.team.domain.teamMember

import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.TeamRoleQueries
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.global.policy.RolePolicy
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.themoment.sdk.exception.ExpectedException

@Service
class TeamPermissionGuard(
    private val teamMemberRepository: TeamMemberRepository,
    private val teamRoleQueries: TeamRoleQueries,
) {
    fun requireMemberExists(teamId: Long, userId: Long) {
        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)
        }
    }

    fun requirePermission(
        teamId: Long,
        accountId: Long,
        permission: String,
        deniedMessage: String = "권한이 없습니다.",
    ): List<TeamRoleResponse> {
        requireMemberExists(teamId, accountId)
        val roles = teamRoleQueries.getMemberRoles(teamId, accountId)
        if (roles.none { RolePolicy.allows(it.permissions, permission) }) {
            throw ExpectedException(deniedMessage, HttpStatus.FORBIDDEN)
        }
        return roles
    }

    fun requireLowerMemberPriority(teamId: Long, actorId: Long, targetId: Long) {
        val actorPriority =
            teamRoleQueries.getMemberRoles(teamId, actorId).maxOfOrNull { it.priority } ?: Int.MIN_VALUE
        val targetPriority =
            teamRoleQueries.getMemberRoles(teamId, targetId).maxOfOrNull { it.priority } ?: Int.MIN_VALUE
        if (targetPriority >= actorPriority) {
            throw ExpectedException("자신보다 높거나 같은 역할의 멤버는 제거할 수 없습니다.", HttpStatus.FORBIDDEN)
        }
    }
}
