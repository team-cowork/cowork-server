package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.teamAccess.TeamDirectory
import com.cowork.team.domain.teamMember.TeamPermissionGuard
import com.cowork.team.domain.teamMember.event.TeamMemberEventPublisher
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamMember.service.DeleteTeamMemberService
import com.cowork.team.global.outbox.NotificationPublisher
import com.cowork.team.global.outbox.NotificationTriggerEvent
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class DeleteTeamMemberServiceImpl(
    private val teamDirectory: TeamDirectory,
    private val teamMemberRepository: TeamMemberRepository,
    private val notificationPublisher: NotificationPublisher,
    private val teamMemberEventPublisher: TeamMemberEventPublisher,
    private val teamPermissionGuard: TeamPermissionGuard,
) : DeleteTeamMemberService {

    @Transactional
    override fun execute(actorId: Long, teamId: Long, targetUserId: Long) {
        val team = teamDirectory.lockOrThrow(teamId)
        val actorMember = teamMemberRepository.findByTeamIdAndUserIdForUpdate(teamId, actorId)
            ?: throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)

        val isSelf = actorId == targetUserId
        if (!isSelf) teamPermissionGuard.requirePermission(teamId, actorId, "kick_members")

        val targetMember = if (isSelf) {
            actorMember
        } else {
            teamMemberRepository.findByTeamIdAndUserIdForUpdate(teamId, targetUserId)
                ?: throw ExpectedException("해당 멤버를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }

        if (team.ownerId == targetMember.userId) {
            throw ExpectedException("소유권을 이양하기 전에는 소유자가 팀을 탈퇴하거나 제거될 수 없습니다.", HttpStatus.FORBIDDEN)
        }
        if (!isSelf) teamPermissionGuard.requireLowerMemberPriority(teamId, actorMember.userId, targetMember.userId)

        teamMemberEventPublisher.publishDelete(targetMember)
        teamMemberRepository.delete(targetMember)
        notificationPublisher.publish(
            teamId,
            NotificationTriggerEvent(
                type = "MEMBER_REMOVED",
                targetUserIds = listOf(targetUserId),
                data = mapOf("teamId" to teamId),
            ),
        )
    }
}
