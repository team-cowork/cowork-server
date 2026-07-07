package com.cowork.team.domain.teamInvite.service

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.themoment.sdk.exception.ExpectedException

@Service
class TeamInviteAccessGuard(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
) {

    fun findTeamOrThrow(teamId: Long): Team = teamRepository.findById(teamId).orElseThrow {
        ExpectedException("팀을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
    }

    fun requireMember(teamId: Long, userId: Long): TeamMember =
        teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
            ?: throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)
}
