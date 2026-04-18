package com.cowork.team.dto

import com.cowork.team.domain.TeamMember
import java.time.LocalDateTime

data class TeamMemberResponse(
    val id: Long,
    val userId: Long,
    val role: String,
    val joinedAt: LocalDateTime,
) {
    companion object {
        fun of(member: TeamMember): TeamMemberResponse = TeamMemberResponse(
            id = member.id,
            userId = member.userId,
            role = member.role.name,
            joinedAt = member.joinedAt,
        )
    }
}
