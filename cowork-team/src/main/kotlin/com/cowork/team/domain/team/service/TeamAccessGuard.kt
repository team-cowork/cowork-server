package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.themoment.sdk.exception.ExpectedException

@Service
class TeamAccessGuard(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
) {

    fun findTeamOrThrow(teamId: Long): Team = teamRepository.findById(teamId).orElseThrow {
        ExpectedException("팀을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
    }

    fun requireRole(teamId: Long, userId: Long, vararg roles: TeamRole): TeamMember =
        teamMemberRepository.findByTeamIdAndUserIdAndRoleIn(teamId, userId, roles.toList())
            ?: throw ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)
}
