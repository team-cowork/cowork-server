package com.cowork.team.domain.teamRole.service

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse

interface GetTeamRolesService {
    fun execute(teamId: Long): List<TeamRoleResponse>
}
