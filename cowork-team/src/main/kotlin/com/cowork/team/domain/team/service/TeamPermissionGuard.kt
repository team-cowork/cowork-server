package com.cowork.team.domain.team.service

import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionReader
import com.cowork.team.global.policy.RolePolicy
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.themoment.sdk.exception.ExpectedException

@Service
class TeamPermissionGuard(private val members: TeamMemberRepository, private val roles: TeamRoleProjectionReader) {
    fun requirePermission(teamId: Long, accountId: Long, permission: String): TeamMember {
        val member = members.findByTeamIdAndUserId(teamId, accountId)
            ?: throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)
        if (roles.getMemberRoles(teamId, accountId).none { RolePolicy.allows(it.permissions, permission) }) {
            throw ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)
        }
        return member
    }

    fun requireLowerMemberPriority(teamId: Long, actor: TeamMember, target: TeamMember) {
        val actorPriority = roles.getMemberRoles(teamId, actor.userId).maxOfOrNull { it.priority } ?: Int.MIN_VALUE
        val targetPriority = roles.getMemberRoles(teamId, target.userId).maxOfOrNull { it.priority } ?: Int.MIN_VALUE
        if (targetPriority >= actorPriority) {
            throw ExpectedException("자신보다 높거나 같은 역할의 멤버는 제거할 수 없습니다.", HttpStatus.FORBIDDEN)
        }
    }
}
