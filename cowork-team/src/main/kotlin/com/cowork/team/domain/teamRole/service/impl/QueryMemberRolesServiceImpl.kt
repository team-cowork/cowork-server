package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.service.QueryMemberRolesService
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import com.cowork.team.global.client.PreferenceTeamRoleClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryMemberRolesServiceImpl(
    private val preferenceTeamRoleClient: PreferenceTeamRoleClient,
    private val teamRoleAccessGuard: TeamRoleAccessGuard,
) : QueryMemberRolesService {

    @Transactional(readOnly = true)
    override fun execute(teamId: Long, userId: Long): List<TeamRoleResponse> {
        teamRoleAccessGuard.requireMemberExists(teamId, userId)
        return preferenceTeamRoleClient.getMemberRoles(teamId, userId)
    }
}
