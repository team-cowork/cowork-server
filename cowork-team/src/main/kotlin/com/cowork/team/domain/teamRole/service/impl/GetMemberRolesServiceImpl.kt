package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.service.GetMemberRolesService
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import com.cowork.team.global.client.PreferenceTeamRoleClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetMemberRolesServiceImpl(
    private val preferenceTeamRoleClient: PreferenceTeamRoleClient,
    private val teamRoleAccessGuard: TeamRoleAccessGuard,
) : GetMemberRolesService {

    override fun getMemberRoles(teamId: Long, userId: Long): List<TeamRoleResponse> {
        teamRoleAccessGuard.requireMemberExists(teamId, userId)
        return preferenceTeamRoleClient.getMemberRoles(teamId, userId)
    }
}
