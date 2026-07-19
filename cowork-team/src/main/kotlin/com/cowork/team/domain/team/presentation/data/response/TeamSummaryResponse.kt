package com.cowork.team.domain.team.presentation.data.response

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.teamRole.entity.TeamRole
import io.swagger.v3.oas.annotations.media.Schema

data class TeamSummaryResponse(
    @field:Schema(description = "팀 ID", example = "1")
    val id: Long,
    @field:Schema(description = "팀 이름", example = "코워크팀")
    val name: String,
    @field:Schema(description = "팀 아이콘 URL", example = "https://example.com/icon.png")
    val iconUrl: String?,
    @field:Schema(description = "내 역할", example = "MEMBER", allowableValues = ["OWNER", "ADMIN", "MEMBER"])
    val myRole: String,
) {
    companion object {
        fun of(team: Team, myRole: TeamRole): TeamSummaryResponse = TeamSummaryResponse(
            id = team.id,
            name = team.name,
            iconUrl = team.iconUrl,
            myRole = myRole.name,
        )
    }
}
