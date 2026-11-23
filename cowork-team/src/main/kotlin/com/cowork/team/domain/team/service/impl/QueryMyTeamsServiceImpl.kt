package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.presentation.data.response.TeamSummaryResponse
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.QueryMyTeamsService
import com.cowork.team.domain.teamMember.TeamMemberships
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryMyTeamsServiceImpl(
    private val teamMemberships: TeamMemberships,
    private val teamRepository: TeamRepository,
) : QueryMyTeamsService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long): List<TeamSummaryResponse> {
        val rolesByTeam = teamMemberships.rolesByTeam(userId)
        val teams = teamRepository.findAllById(rolesByTeam.keys).associateBy { it.id }
        return rolesByTeam.mapNotNull { (teamId, role) ->
            teams[teamId]?.let { TeamSummaryResponse.of(it, role) }
        }
    }
}
