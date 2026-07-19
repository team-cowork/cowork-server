package com.cowork.team.domain.teamRole.service

import com.cowork.team.domain.teamRole.presentation.data.request.CreateTeamRoleRequest
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse

interface CreateTeamRoleService {
    fun execute(actorId: Long, teamId: Long, request: CreateTeamRoleRequest): TeamRoleResponse
}
