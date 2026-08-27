package com.cowork.team.domain.teamRole.service

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleOperationResponse

interface RevokeTeamRoleService {
    fun execute(
        actorId: Long,
        teamId: Long,
        targetUserId: Long,
        roleId: Long,
        idempotencyKey: String,
    ): TeamRoleOperationResponse
}
