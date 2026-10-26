package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRolePermissionGroupResponse
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRolePermissionResponse
import com.cowork.team.domain.teamRole.service.QueryTeamRolePermissionsService
import com.cowork.team.domain.teamRole.service.support.TeamRolePermission
import com.cowork.team.domain.teamRole.service.support.TeamRolePermissionGroup
import org.springframework.stereotype.Service

@Service
class QueryTeamRolePermissionsServiceImpl : QueryTeamRolePermissionsService {

    override fun execute(): List<TeamRolePermissionGroupResponse> = GROUPS

    private companion object {
        val GROUPS: List<TeamRolePermissionGroupResponse> = TeamRolePermissionGroup.entries.map { group ->
            TeamRolePermissionGroupResponse(
                key = group,
                title = group.title,
                permissions = TeamRolePermission.entries.filter {
                    it.group == group
                }.map(TeamRolePermissionResponse::of),
            )
        }
    }
}
