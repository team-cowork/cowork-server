package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.presentation.data.request.UpdateTeamRequest
import com.cowork.team.domain.team.presentation.data.response.TeamResponse
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.team.service.UpdateTeamService
import com.cowork.team.domain.teamRole.entity.TeamRole
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class UpdateTeamServiceImpl(private val teamAccessGuard: TeamAccessGuard) : UpdateTeamService {

    override fun execute(userId: Long, teamId: Long, request: UpdateTeamRequest): TeamResponse {
        teamAccessGuard.requireRole(teamId, userId, TeamRole.OWNER, TeamRole.ADMIN)
        val team = teamAccessGuard.findTeamOrThrow(teamId)
        team.update(request.name, request.description, request.iconUrl)
        return TeamResponse.of(team)
    }
}
