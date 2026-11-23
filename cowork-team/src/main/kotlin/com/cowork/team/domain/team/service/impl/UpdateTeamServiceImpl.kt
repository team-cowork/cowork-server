package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.presentation.data.request.UpdateTeamRequest
import com.cowork.team.domain.team.presentation.data.response.TeamResponse
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.team.service.TeamIconOwnershipManager
import com.cowork.team.domain.team.service.UpdateTeamService
import com.cowork.team.domain.teamMember.TeamMemberships
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UpdateTeamServiceImpl(
    private val teamIconOwnershipManager: TeamIconOwnershipManager,
    private val teamMemberships: TeamMemberships,
    private val teamEventPublisher: TeamEventPublisher,
    private val teamAccessGuard: TeamAccessGuard,
) : UpdateTeamService {

    @Transactional
    override fun execute(userId: Long, teamId: Long, request: UpdateTeamRequest): TeamResponse {
        val team = teamAccessGuard.findTeamForUpdateOrThrow(teamId)
        teamAccessGuard.requirePermission(teamId, userId, "manage_team")
        request.iconUrl?.let { teamIconOwnershipManager.replace(userId, team, it) }
        team.update(request.name, request.description, null)
        teamEventPublisher.publishUpdated(team, userId)
        teamMemberships.publishTeamUpdate(teamId)
        return TeamResponse.of(team)
    }
}
