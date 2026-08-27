package com.cowork.team.domain.teamRole.service

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleOperationResponse

interface QueryTeamRoleOperationService {
    fun execute(actorId: Long, teamId: Long, operationId: String): TeamRoleOperationResponse
}
