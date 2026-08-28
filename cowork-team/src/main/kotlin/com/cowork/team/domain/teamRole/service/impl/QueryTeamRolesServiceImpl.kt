package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionReader
import com.cowork.team.domain.teamRole.service.QueryTeamRolesService
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryTeamRolesServiceImpl(
    private val teamRoleProjectionReader: TeamRoleProjectionReader,
    private val teamRoleAccessGuard: TeamRoleAccessGuard,
) : QueryTeamRolesService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, teamId: Long): List<TeamRoleResponse> {
        teamRoleAccessGuard.findMemberOrThrow(teamId, userId)
        return teamRoleProjectionReader.getRoles(teamId)
    }
}
