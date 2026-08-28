package com.cowork.team.domain.teamRole.service

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleOperationResponse

interface DeleteTeamRoleService {
    fun execute(actorId: Long, teamId: Long, roleId: Long, idempotencyKey: String): TeamRoleOperationResponse
}
