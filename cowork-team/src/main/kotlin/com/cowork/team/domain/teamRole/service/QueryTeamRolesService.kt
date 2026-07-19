package com.cowork.team.domain.teamRole.service

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse

interface QueryTeamRolesService {
    fun execute(teamId: Long): List<TeamRoleResponse>
}
