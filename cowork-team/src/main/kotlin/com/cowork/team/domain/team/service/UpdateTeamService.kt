package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.presentation.data.request.UpdateTeamRequest
import com.cowork.team.domain.team.presentation.data.response.TeamResponse

interface UpdateTeamService {
    fun execute(userId: Long, teamId: Long, request: UpdateTeamRequest): TeamResponse
}
