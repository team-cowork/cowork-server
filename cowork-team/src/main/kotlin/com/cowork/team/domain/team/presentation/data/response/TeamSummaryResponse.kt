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
    // TODO(2026-11-17T06:59:44Z): Remove this legacy label after clients use assigned role policies and ownerId.
    @field:Schema(description = "권한 판정에 사용하지 않는 멤버십 표시값", deprecated = true)
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
