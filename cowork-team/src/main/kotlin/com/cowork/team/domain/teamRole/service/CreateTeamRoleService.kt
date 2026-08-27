package com.cowork.team.domain.teamRole.service

import com.cowork.team.domain.teamRole.presentation.data.request.CreateTeamRoleRequest
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleOperationResponse

interface CreateTeamRoleService {
    fun execute(
        actorId: Long,
        teamId: Long,
        idempotencyKey: String,
        request: CreateTeamRoleRequest,
    ): TeamRoleOperationResponse
}
