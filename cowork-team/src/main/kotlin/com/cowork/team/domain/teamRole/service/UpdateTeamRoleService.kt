package com.cowork.team.domain.teamRole.service

import com.cowork.team.domain.teamRole.presentation.data.request.UpdateTeamRoleRequest
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse

interface UpdateTeamRoleService {
    fun execute(actorId: Long, teamId: Long, roleId: Long, request: UpdateTeamRoleRequest): TeamRoleResponse
}
