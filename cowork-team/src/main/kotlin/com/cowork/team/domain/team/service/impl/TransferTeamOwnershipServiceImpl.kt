package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.presentation.data.response.TeamResponse
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.team.service.TransferTeamOwnershipService
import com.cowork.team.domain.teamMember.TeamMemberships
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class TransferTeamOwnershipServiceImpl(
    private val teamAccessGuard: TeamAccessGuard,
    private val teamMemberships: TeamMemberships,
    private val teamEventPublisher: TeamEventPublisher,
) : TransferTeamOwnershipService {
    @Transactional
    override fun execute(actorId: Long, teamId: Long, targetUserId: Long): TeamResponse {
        val team = teamAccessGuard.findTeamForUpdateOrThrow(teamId)
        teamAccessGuard.requirePermission(teamId, actorId, "transfer_team_ownership")
        // The source owner fences a stale root-policy projection after an earlier transfer.
        if (team.ownerId != actorId) throw ExpectedException("현재 소유자만 소유권을 이양할 수 있습니다.", HttpStatus.FORBIDDEN)
        if (actorId == targetUserId) throw ExpectedException("다른 팀 멤버에게 소유권을 이양해야 합니다.", HttpStatus.BAD_REQUEST)
        teamMemberships.transferOwnership(teamId, actorId, targetUserId) {
            team.ownerId = targetUserId
            teamEventPublisher.publishUpdated(team, actorId)
        }
        return TeamResponse.of(team)
    }
}
