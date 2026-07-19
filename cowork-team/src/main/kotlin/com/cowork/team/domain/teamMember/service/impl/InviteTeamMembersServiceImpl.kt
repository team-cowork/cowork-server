package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.team.event.TeamEventPayload
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.teamInvite.presentation.data.request.InviteMembersRequest
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.presentation.data.response.TeamMemberResponse
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamMember.service.InviteTeamMembersService
import com.cowork.team.domain.teamMember.service.TeamMemberAccessGuard
import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.global.support.afterCommit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class InviteTeamMembersServiceImpl(
    private val teamMemberRepository: TeamMemberRepository,
    private val teamEventPublisher: TeamEventPublisher,
    private val teamMemberAccessGuard: TeamMemberAccessGuard,
) : InviteTeamMembersService {

    @Transactional
    override fun execute(actorId: Long, teamId: Long, request: InviteMembersRequest): List<TeamMemberResponse> {
        teamMemberAccessGuard.requireRole(teamId, actorId, TeamRole.OWNER, TeamRole.ADMIN)
        val team = teamMemberAccessGuard.findTeamOrThrow(teamId)

        val existingUserIds = teamMemberRepository.findAllByTeamId(teamId).map { it.userId }.toSet()
        val newMembers = request.userIds
            .filter { it !in existingUserIds }
            .map { userId -> TeamMember(team = team, userId = userId, role = TeamRole.MEMBER) }

        val savedMembers = teamMemberRepository.saveAll(newMembers)

        if (savedMembers.isNotEmpty()) {
            val payload = TeamEventPayload(
                eventType = "MEMBER_INVITED",
                teamId = teamId,
                teamName = team.name,
                actorUserId = actorId,
                targetUserIds = savedMembers.map { it.userId },
            )
            afterCommit { teamEventPublisher.publishLifecycle(payload) }
        }

        return savedMembers.map { TeamMemberResponse.of(it) }
    }
}
