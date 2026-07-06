package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.presentation.data.response.TeamResponse
import com.cowork.team.domain.team.service.QueryTeamService
import com.cowork.team.domain.team.service.TeamAccessGuard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class QueryTeamServiceImpl(private val teamAccessGuard: TeamAccessGuard) : QueryTeamService {

    override fun execute(teamId: Long): TeamResponse = TeamResponse.of(teamAccessGuard.findTeamOrThrow(teamId))
}
