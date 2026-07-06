package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.presentation.data.response.TeamResponse

interface QueryTeamService {
    fun execute(teamId: Long): TeamResponse
}
