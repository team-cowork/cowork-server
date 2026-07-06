package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.presentation.data.request.CreateTeamRequest
import com.cowork.team.domain.team.presentation.data.response.TeamResponse

interface CreateTeamService {
    fun execute(ownerId: Long, request: CreateTeamRequest): TeamResponse
}
