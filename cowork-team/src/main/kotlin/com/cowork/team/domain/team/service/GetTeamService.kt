package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.presentation.data.response.TeamResponse

interface GetTeamService {
    fun getTeam(teamId: Long): TeamResponse
}
