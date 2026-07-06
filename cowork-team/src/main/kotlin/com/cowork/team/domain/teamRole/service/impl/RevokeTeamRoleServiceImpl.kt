package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.service.RevokeTeamRoleService
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import com.cowork.team.domain.teamRole.service.support.TeamRoleLookupSupport
import com.cowork.team.global.client.PreferenceTeamRoleClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RevokeTeamRoleServiceImpl(
    private val preferenceTeamRoleClient: PreferenceTeamRoleClient,
    private val teamRoleAccessGuard: TeamRoleAccessGuard,
    private val teamRoleLookupSupport: TeamRoleLookupSupport,
) : RevokeTeamRoleService {

    @Transactional(readOnly = true)
    override fun execute(actorId: Long, teamId: Long, targetUserId: Long, roleId: Long) {
        val actor = teamRoleAccessGuard.requireManageRoles(teamId, actorId)
        teamRoleAccessGuard.requireMemberExists(teamId, targetUserId)
        val role = teamRoleLookupSupport.findRoleOrThrow(teamId, roleId)
        teamRoleAccessGuard.requireManageablePriority(actor, role)
        preferenceTeamRoleClient.revokeRole(teamId, targetUserId, roleId)
    }
}
