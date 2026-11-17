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
    private val access: TeamAccessGuard,
    private val members: TeamMemberRepository,
    private val teamEvents: TeamEventPublisher,
    private val memberEvents: TeamMemberEventPublisher,
) : TransferTeamOwnershipService {
    @Transactional
    override fun execute(actorId: Long, teamId: Long, targetUserId: Long): TeamResponse {
        val team = access.findTeamForUpdateOrThrow(teamId)
        access.requirePermission(teamId, actorId, "transfer_team_ownership")
        // The source owner fences a stale root-policy projection after an earlier transfer.
        if (team.ownerId != actorId) throw ExpectedException("현재 소유자만 소유권을 이양할 수 있습니다.", HttpStatus.FORBIDDEN)
        if (actorId == targetUserId) throw ExpectedException("다른 팀 멤버에게 소유권을 이양해야 합니다.", HttpStatus.BAD_REQUEST)
        val actor = members.findByTeamIdAndUserIdForUpdate(teamId, actorId)
            ?: throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)
        val target = members.findByTeamIdAndUserIdForUpdate(teamId, targetUserId)
            ?: throw ExpectedException("소유권을 받을 팀 멤버를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        team.ownerId = targetUserId
        actor.changeRole(TeamRole.MEMBER)
        target.changeRole(TeamRole.OWNER)
        teamEvents.publishUpdated(team, actorId)
        memberEvents.publishUpsert(actor)
        memberEvents.publishUpsert(target)
        return TeamResponse.of(team)
    }
}
