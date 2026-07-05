package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.service.AssignTeamRoleService
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import com.cowork.team.domain.teamRole.service.support.TeamRoleLookupSupport
import com.cowork.team.global.client.PreferenceTeamRoleClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AssignTeamRoleServiceImpl(
    private val preferenceTeamRoleClient: PreferenceTeamRoleClient,
    private val teamRoleAccessGuard: TeamRoleAccessGuard,
    private val teamRoleLookupSupport: TeamRoleLookupSupport,
) : AssignTeamRoleService {

    override fun assignRole(actorId: Long, teamId: Long, targetUserId: Long, roleId: Long): TeamRoleResponse {
        val actor = teamRoleAccessGuard.requireManageRoles(teamId, actorId)
        teamRoleAccessGuard.requireMemberExists(teamId, targetUserId)
        val role = teamRoleLookupSupport.findRoleOrThrow(teamId, roleId)
        teamRoleAccessGuard.requireManageablePriority(actor, role)
        return preferenceTeamRoleClient.assignRole(teamId, roleId, mapOf("accountId" to targetUserId))
    }
}
