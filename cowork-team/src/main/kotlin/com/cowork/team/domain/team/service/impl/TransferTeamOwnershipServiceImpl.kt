package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.team.presentation.data.response.TeamResponse
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.team.service.TransferTeamOwnershipService
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class TransferTeamOwnershipServiceImpl(
    private val teamAccessGuard: TeamAccessGuard,
    private val teamMemberRepository: TeamMemberRepository,
    private val teamEventPublisher: TeamEventPublisher,
    private val teamMemberEventPublisher: TeamMemberEventPublisher,
) : TransferTeamOwnershipService {
    @Transactional
    override fun execute(actorId: Long, teamId: Long, targetUserId: Long): TeamResponse {
        val team = teamAccessGuard.findTeamForUpdateOrThrow(teamId)
        teamAccessGuard.requirePermission(teamId, actorId, "transfer_team_ownership")
        // The source owner fences a stale root-policy projection after an earlier transfer.
        if (team.ownerId != actorId) throw ExpectedException("현재 소유자만 소유권을 이양할 수 있습니다.", HttpStatus.FORBIDDEN)
        if (actorId == targetUserId) throw ExpectedException("다른 팀 멤버에게 소유권을 이양해야 합니다.", HttpStatus.BAD_REQUEST)
        val actor = teamMemberRepository.findByTeamIdAndUserIdForUpdate(teamId, actorId)
            ?: throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)
        val target = teamMemberRepository.findByTeamIdAndUserIdForUpdate(teamId, targetUserId)
            ?: throw ExpectedException("소유권을 받을 팀 멤버를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        team.ownerId = targetUserId
        actor.changeRole(TeamRole.MEMBER)
        target.changeRole(TeamRole.OWNER)
        teamEventPublisher.publishUpdated(team, actorId)
        teamMemberEventPublisher.publishUpsert(actor)
        teamMemberEventPublisher.publishUpsert(target)
        return TeamResponse.of(team)
    }
}
