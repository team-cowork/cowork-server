package com.cowork.team.domain.teamRole.service

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRolePermissionGroupResponse

interface QueryTeamRolePermissionsService {
    fun execute(): List<TeamRolePermissionGroupResponse>
}
