package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.presentation.data.request.CreateTeamRequest
import com.cowork.team.domain.team.presentation.data.response.TeamResponse

interface CreateTeamService {
    fun createTeam(ownerId: Long, request: CreateTeamRequest): TeamResponse
}
