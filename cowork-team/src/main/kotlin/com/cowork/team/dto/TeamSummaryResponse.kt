package com.cowork.team.dto

import com.cowork.team.domain.Team
import com.cowork.team.domain.TeamRole

data class TeamSummaryResponse(
    val id: Long,
    val name: String,
    val iconUrl: String?,
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