package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.service.DeleteTeamRoleService
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import com.cowork.team.domain.teamRole.service.support.TeamRoleLookupSupport
import com.cowork.team.global.client.PreferenceTeamRoleClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class DeleteTeamRoleServiceImpl(
    private val preferenceTeamRoleClient: PreferenceTeamRoleClient,
    private val teamRoleAccessGuard: TeamRoleAccessGuard,
    private val teamRoleLookupSupport: TeamRoleLookupSupport,
) : DeleteTeamRoleService {

    override fun deleteRole(actorId: Long, teamId: Long, roleId: Long) {
        val actor = teamRoleAccessGuard.requireManageRoles(teamId, actorId)
        val role = teamRoleLookupSupport.findRoleOrThrow(teamId, roleId)
        teamRoleAccessGuard.requireManageablePriority(actor, role)
        preferenceTeamRoleClient.deleteRole(teamId, roleId)
    }
}
