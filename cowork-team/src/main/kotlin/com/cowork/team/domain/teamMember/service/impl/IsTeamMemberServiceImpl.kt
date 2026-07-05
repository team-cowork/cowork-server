package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamMember.service.IsTeamMemberService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class IsTeamMemberServiceImpl(
    private val teamMemberRepository: TeamMemberRepository,
) : IsTeamMemberService {

    override fun isMember(teamId: Long, userId: Long): Boolean =
        teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)
}
