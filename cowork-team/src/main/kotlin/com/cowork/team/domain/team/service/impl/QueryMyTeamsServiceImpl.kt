package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.presentation.data.response.TeamSummaryResponse
import com.cowork.team.domain.team.service.QueryMyTeamsService
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class QueryMyTeamsServiceImpl(private val teamMemberRepository: TeamMemberRepository) : QueryMyTeamsService {

    override fun execute(userId: Long): List<TeamSummaryResponse> = teamMemberRepository.findAllByUserIdWithTeam(userId)
        .map { m -> TeamSummaryResponse.of(m.team, m.role) }
}
