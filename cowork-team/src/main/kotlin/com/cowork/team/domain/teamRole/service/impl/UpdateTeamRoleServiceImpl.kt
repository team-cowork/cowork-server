package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.presentation.data.request.UpdateTeamRoleRequest
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import com.cowork.team.domain.teamRole.service.UpdateTeamRoleService
import com.cowork.team.domain.teamRole.service.support.TeamRoleLookupSupport
import com.cowork.team.global.client.PreferenceTeamRoleClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class UpdateTeamRoleServiceImpl(
    private val preferenceTeamRoleClient: PreferenceTeamRoleClient,
    private val teamRoleAccessGuard: TeamRoleAccessGuard,
    private val teamRoleLookupSupport: TeamRoleLookupSupport,
) : UpdateTeamRoleService {

    override fun updateRole(actorId: Long, teamId: Long, roleId: Long, request: UpdateTeamRoleRequest): TeamRoleResponse {
        val actor = teamRoleAccessGuard.requireManageRoles(teamId, actorId)
        val currentRole = teamRoleLookupSupport.findRoleOrThrow(teamId, roleId)
        teamRoleAccessGuard.requireManageablePriority(actor, currentRole)
        request.priority?.let {
            teamRoleAccessGuard.requireManageablePriority(actor, currentRole.copy(priority = it))
        }
        return preferenceTeamRoleClient.updateRole(teamId, roleId, request)
    }
}
