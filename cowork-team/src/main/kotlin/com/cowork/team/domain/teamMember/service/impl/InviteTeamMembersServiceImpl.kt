package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.team.event.NotificationTriggerEvent
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.teamInvite.presentation.data.request.InviteMembersRequest
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.presentation.data.response.TeamMemberResponse
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamMember.service.InviteTeamMembersService
import com.cowork.team.domain.teamMember.service.TeamMemberAccessGuard
import com.cowork.team.domain.teamRole.entity.TeamRole
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class InviteTeamMembersServiceImpl(
    private val teamMemberRepository: TeamMemberRepository,
    private val teamEventPublisher: TeamEventPublisher,
    private val teamMemberEventPublisher: TeamMemberEventPublisher,
    private val teamMemberAccessGuard: TeamMemberAccessGuard,
) : InviteTeamMembersService {

    @Transactional
    override fun execute(actorId: Long, teamId: Long, request: InviteMembersRequest): List<TeamMemberResponse> {
        val team = teamMemberAccessGuard.findTeamForUpdateOrThrow(teamId)
        teamMemberAccessGuard.requireRole(teamId, actorId, TeamRole.OWNER, TeamRole.ADMIN)

        val existingUserIds = teamMemberRepository.findAllByTeamIdForUpdate(teamId).map { it.userId }.toSet()
        val newMembers = request.userIds
            .distinct()
            .filter { it !in existingUserIds }
            .map { userId -> TeamMember(team = team, userId = userId, role = TeamRole.MEMBER) }

        val savedMembers = teamMemberRepository.saveAll(newMembers)

        if (savedMembers.isNotEmpty()) {
            teamEventPublisher.publishNotification(
                teamId,
                NotificationTriggerEvent(
                    type = "MEMBER_INVITED",
                    targetUserIds = savedMembers.map { it.userId },
                    data = mapOf("teamId" to teamId),
                ),
            )
            savedMembers.forEach(teamMemberEventPublisher::publishUpsert)
        }

        return savedMembers.map { TeamMemberResponse.of(it) }
    }
}
