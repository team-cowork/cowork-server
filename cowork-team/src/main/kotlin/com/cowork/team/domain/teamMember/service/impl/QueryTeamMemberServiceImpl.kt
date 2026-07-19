package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamMember.service.QueryTeamMemberService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryTeamMemberServiceImpl(private val teamMemberRepository: TeamMemberRepository) : QueryTeamMemberService {

    @Transactional(readOnly = true)
    override fun execute(teamId: Long, userId: Long): Boolean =
        teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)
}
