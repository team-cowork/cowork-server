package com.cowork.team.domain.team.service

import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionReader
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.themoment.sdk.exception.ExpectedException

@Service
class TeamPermissionGuard(private val members: TeamMemberRepository, private val roles: TeamRoleProjectionReader) {
    fun requirePermission(teamId: Long, accountId: Long, permission: String): TeamMember {
        val member = members.findByTeamIdAndUserId(teamId, accountId)
            ?: throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)
        if (member.role == TeamRole.OWNER || member.role == TeamRole.ADMIN) return member
        if (roles.getMemberRoles(teamId, accountId).none { it.permissions[permission] == true }) {
            throw ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)
        }
        return member
    }
}
