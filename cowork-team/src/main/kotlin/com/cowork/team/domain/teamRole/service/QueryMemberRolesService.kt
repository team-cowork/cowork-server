package com.cowork.team.domain.teamRole.service

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse

interface QueryMemberRolesService {
    fun execute(teamId: Long, userId: Long): List<TeamRoleResponse>
}
