package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.presentation.data.response.TeamSummaryResponse

interface QueryMyTeamsService {
    fun execute(userId: Long): List<TeamSummaryResponse>
}
