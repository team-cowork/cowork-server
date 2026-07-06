package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.service.QueryTeamRolesService
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import com.cowork.team.global.client.PreferenceTeamRoleClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class QueryTeamRolesServiceImpl(
    private val preferenceTeamRoleClient: PreferenceTeamRoleClient,
    private val teamRoleAccessGuard: TeamRoleAccessGuard,
) : QueryTeamRolesService {

    override fun execute(teamId: Long): List<TeamRoleResponse> {
        teamRoleAccessGuard.requireTeam(teamId)
        return preferenceTeamRoleClient.getRoles(teamId)
    }
}
