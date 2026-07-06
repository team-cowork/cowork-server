package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.presentation.data.response.TeamSummaryResponse
import com.cowork.team.domain.team.service.GetMyTeamsService
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetMyTeamsServiceImpl(private val teamMemberRepository: TeamMemberRepository) : GetMyTeamsService {

    override fun execute(userId: Long): List<TeamSummaryResponse> = teamMemberRepository.findAllByUserIdWithTeam(userId)
        .map { m -> TeamSummaryResponse.of(m.team, m.role) }
}
