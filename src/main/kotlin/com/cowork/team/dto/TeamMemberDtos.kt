package com.cowork.team.dto

import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamMemberRole
import com.cowork.user.dto.UserSummaryResponse
import java.time.LocalDateTime

data class AddTeamMemberRequest(
    val userId: Long,
)

data class ChangeTeamMemberRoleRequest(
    val role: TeamMemberRole,
)

data class TeamMemberResponse(
    val userId: Long,
    val role: TeamMemberRole,
    val joinedAt: LocalDateTime,
    val user: UserSummaryResponse?,
) {
    companion object {
        fun of(member: TeamMember, user: UserSummaryResponse?) = TeamMemberResponse(
            userId = member.userId,
            role = member.role,
            joinedAt = member.joinedAt,
            user = user,
        )
    }
}

data class TeamMemberExistsResponse(
    val exists: Boolean,
)
