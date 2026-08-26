package com.cowork.team.domain.teamRole.service.impl

import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionReader
import com.cowork.team.domain.teamRole.service.QueryMemberRolesService
import com.cowork.team.domain.teamRole.service.TeamRoleAccessGuard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryMemberRolesServiceImpl(
    private val teamRoleProjectionReader: TeamRoleProjectionReader,
    private val teamRoleAccessGuard: TeamRoleAccessGuard,
) : QueryMemberRolesService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, teamId: Long, targetUserId: Long): List<TeamRoleResponse> {
        teamRoleAccessGuard.findMemberOrThrow(teamId, userId)
        teamRoleAccessGuard.requireMemberExists(teamId, targetUserId)
        return teamRoleProjectionReader.getMemberRoles(teamId, targetUserId)
    }
}
