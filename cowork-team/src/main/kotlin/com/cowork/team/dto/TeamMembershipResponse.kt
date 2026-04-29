package com.cowork.team.dto

import com.cowork.team.domain.TeamMember
import io.swagger.v3.oas.annotations.media.Schema

data class TeamMembershipResponse(
    @Schema(description = "팀 ID", example = "1")
    val teamId: Long,
    @Schema(description = "사용자 ID", example = "42")
    val userId: Long,
    @Schema(description = "역할", example = "MEMBER", allowableValues = ["OWNER", "ADMIN", "MEMBER"])
    val role: String,
) {
    companion object {
        fun of(member: TeamMember): TeamMembershipResponse = TeamMembershipResponse(
            teamId = member.team.id,
            userId = member.userId,
            role = member.role.name,
        )
    }
}
