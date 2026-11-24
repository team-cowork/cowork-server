package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.teamAccess.TeamDirectory
import com.cowork.team.domain.teamMember.TeamPermissionGuard
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.entity.TeamRole
import com.cowork.team.domain.teamMember.event.TeamMemberEventPublisher
import com.cowork.team.domain.teamMember.presentation.data.response.TeamMemberResponse
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamMember.service.InviteTeamMembersService
import com.cowork.team.global.outbox.NotificationPublisher
import com.cowork.team.global.outbox.NotificationTriggerEvent
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class InviteTeamMembersServiceImpl(
    private val teamMemberRepository: TeamMemberRepository,
    private val notificationPublisher: NotificationPublisher,
    private val teamMemberEventPublisher: TeamMemberEventPublisher,
    private val teamPermissionGuard: TeamPermissionGuard,
    private val teamDirectory: TeamDirectory,
) : InviteTeamMembersService {

    @Transactional
    override fun execute(actorId: Long, teamId: Long, userIds: List<Long>): List<TeamMemberResponse> {
        if (userIds.size > MAX_INVITE_USER_IDS) {
            throw ExpectedException("한 번에 초대할 수 있는 사용자 ID는 ${MAX_INVITE_USER_IDS}개 이하입니다.", HttpStatus.BAD_REQUEST)
        }
        // userId <= 0 은 channel/project consumer 가 격리해 projection 을 not-ready 로 고정한다.
        if (userIds.any { it <= 0 }) {
            throw ExpectedException("사용자 ID는 양수여야 합니다.", HttpStatus.BAD_REQUEST)
        }

        val team = teamDirectory.lockOrThrow(teamId)
        teamPermissionGuard.requirePermission(teamId, actorId, "manage_invites")

        val existingUserIds = teamMemberRepository.findAllByTeamIdForUpdate(teamId).map { it.userId }.toSet()
        val newMembers = userIds
            .distinct()
            .filter { it !in existingUserIds }
            .map { userId -> TeamMember(teamId = team.id, userId = userId, role = TeamRole.MEMBER) }

        val savedMembers = teamMemberRepository.saveAll(newMembers)

        if (savedMembers.isNotEmpty()) {
            notificationPublisher.publish(
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
