package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.presentation.data.response.TeamResponse
import com.cowork.team.domain.team.service.GetTeamService
import com.cowork.team.domain.team.service.TeamAccessGuard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetTeamServiceImpl(
    private val teamAccessGuard: TeamAccessGuard,
) : GetTeamService {

    override fun getTeam(teamId: Long): TeamResponse = TeamResponse.of(teamAccessGuard.findTeamOrThrow(teamId))
}
