package com.cowork.team.domain.teamRole.service

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse

interface AssignTeamRoleService {
    fun assignRole(actorId: Long, teamId: Long, targetUserId: Long, roleId: Long): TeamRoleResponse
}
