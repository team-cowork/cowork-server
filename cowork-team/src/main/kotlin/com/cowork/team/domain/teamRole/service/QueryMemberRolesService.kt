package com.cowork.team.domain.teamRole.service

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse

interface QueryMemberRolesService {
    fun execute(userId: Long, teamId: Long, targetUserId: Long): List<TeamRoleResponse>
}
