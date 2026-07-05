package com.cowork.team.domain.teamRole.service

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse

interface GetMemberRolesService {
    fun getMemberRoles(teamId: Long, userId: Long): List<TeamRoleResponse>
}
