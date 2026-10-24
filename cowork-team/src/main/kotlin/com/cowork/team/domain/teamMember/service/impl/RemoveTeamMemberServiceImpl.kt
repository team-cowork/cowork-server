package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.team.event.NotificationTriggerEvent
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.TeamPermissionGuard
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamMember.service.RemoveTeamMemberService
import com.cowork.team.domain.teamRole.entity.TeamRole
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class RemoveTeamMemberServiceImpl(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val teamEventPublisher: TeamEventPublisher,
    private val teamMemberEventPublisher: TeamMemberEventPublisher,
    private val permissions: TeamPermissionGuard,
) : RemoveTeamMemberService {

    @Transactional
    override fun execute(actorId: Long, teamId: Long, targetUserId: Long) {
        if (teamRepository.findByIdForUpdate(teamId) == null) {
            throw ExpectedException("팀을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
        val actorMember = teamMemberRepository.findByTeamIdAndUserIdForUpdate(teamId, actorId)
            ?: throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)

        val isSelf = actorId == targetUserId
        if (!isSelf) permissions.requirePermission(teamId, actorId, "kick_members")

        val targetMember = if (isSelf) {
            actorMember
        } else {
            teamMemberRepository.findByTeamIdAndUserIdForUpdate(teamId, targetUserId)
                ?: throw ExpectedException("해당 멤버를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }

        if (!isSelf && targetMember.role == TeamRole.ADMIN && actorMember.role != TeamRole.OWNER) {
            throw ExpectedException("관리자는 팀 소유자만 제거할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
        if (targetMember.role == TeamRole.OWNER) {
            throw ExpectedException("OWNER는 팀을 탈퇴하거나 제거할 수 없습니다.", HttpStatus.FORBIDDEN)
        }
        if (!isSelf) permissions.requireLowerMemberPriority(teamId, actorMember, targetMember)

        teamMemberEventPublisher.publishDelete(targetMember)
        teamMemberRepository.delete(targetMember)
        teamEventPublisher.publishNotification(
            teamId,
            NotificationTriggerEvent(
                type = "MEMBER_REMOVED",
                targetUserIds = listOf(targetUserId),
                data = mapOf("teamId" to teamId),
            ),
        )
    }
}
