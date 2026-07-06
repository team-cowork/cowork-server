package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.presentation.data.response.TeamSummaryResponse

interface GetMyTeamsService {
    fun execute(userId: Long): List<TeamSummaryResponse>
}
