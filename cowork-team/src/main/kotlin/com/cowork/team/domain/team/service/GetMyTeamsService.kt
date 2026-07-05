package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.presentation.data.response.TeamSummaryResponse

interface GetMyTeamsService {
    fun getMyTeams(userId: Long): List<TeamSummaryResponse>
}
