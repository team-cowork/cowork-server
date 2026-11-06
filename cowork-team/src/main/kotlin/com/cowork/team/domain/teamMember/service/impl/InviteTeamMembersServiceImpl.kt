package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.team.event.NotificationTriggerEvent
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.teamInvite.presentation.data.request.InviteMembersRequest
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.presentation.data.response.TeamMemberResponse
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamMember.service.InviteTeamMembersService
import com.cowork.team.domain.teamRole.entity.TeamRole
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class InviteTeamMembersServiceImpl(
    private val teamMemberRepository: TeamMemberRepository,
    private val teamEventPublisher: TeamEventPublisher,
    private val teamMemberEventPublisher: TeamMemberEventPublisher,
    private val teamAccessGuard: TeamAccessGuard,
) : InviteTeamMembersService {

    @Transactional
    override fun execute(actorId: Long, teamId: Long, request: InviteMembersRequest): List<TeamMemberResponse> {
        if (request.userIds.size > MAX_INVITE_USER_IDS) {
            throw ExpectedException("한 번에 초대할 수 있는 사용자 ID는 ${MAX_INVITE_USER_IDS}개 이하입니다.", HttpStatus.BAD_REQUEST)
        }
        // userId <= 0 은 channel/project consumer 가 격리해 projection 을 not-ready 로 고정한다.
        if (request.userIds.any { it <= 0 }) {
            throw ExpectedException("사용자 ID는 양수여야 합니다.", HttpStatus.BAD_REQUEST)
        }

        val team = teamAccessGuard.findTeamForUpdateOrThrow(teamId)
        teamAccessGuard.requirePermission(teamId, actorId, "manage_invites")

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

    companion object {
        private const val MAX_INVITE_USER_IDS = 100
    }
}
