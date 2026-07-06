package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.presentation.data.response.TeamResponse

interface GetTeamService {
    fun execute(teamId: Long): TeamResponse
}
