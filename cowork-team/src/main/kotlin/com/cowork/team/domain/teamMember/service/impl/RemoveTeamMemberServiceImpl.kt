package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.team.event.TeamEventPayload
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamMember.service.RemoveTeamMemberService
import com.cowork.team.domain.teamMember.service.TeamMemberAccessGuard
import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.global.client.PreferenceTeamRoleClient
import com.cowork.team.global.support.afterCommit
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class RemoveTeamMemberServiceImpl(
    private val teamMemberRepository: TeamMemberRepository,
    private val preferenceTeamRoleClient: PreferenceTeamRoleClient,
    private val teamEventPublisher: TeamEventPublisher,
    private val teamMemberAccessGuard: TeamMemberAccessGuard,
) : RemoveTeamMemberService {

    @Transactional
    override fun execute(actorId: Long, teamId: Long, targetUserId: Long) {
        val team = teamMemberAccessGuard.findTeamOrThrow(teamId)
        val actorMember = teamMemberRepository.findByTeamIdAndUserId(teamId, actorId)
            ?: throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)

        val isSelf = actorId == targetUserId
        val canManage = actorMember.role == TeamRole.OWNER || actorMember.role == TeamRole.ADMIN

        if (!isSelf && !canManage) {
            throw ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)
        }

        val targetMember = if (isSelf) {
            actorMember
        } else {
            teamMemberRepository.findByTeamIdAndUserId(teamId, targetUserId)
                ?: throw ExpectedException("해당 멤버를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }

        if (targetMember.role == TeamRole.OWNER) {
            throw ExpectedException("OWNER는 팀을 탈퇴하거나 제거할 수 없습니다.", HttpStatus.FORBIDDEN)
        }

        teamMemberRepository.delete(targetMember)
        afterCommit { preferenceTeamRoleClient.deleteMemberRoles(teamId, targetUserId) }

        val payload = TeamEventPayload(
            eventType = "MEMBER_REMOVED",
            teamId = teamId,
            teamName = team.name,
            actorUserId = actorId,
            targetUserIds = listOf(targetUserId),
        )
        afterCommit { teamEventPublisher.publishLifecycle(payload) }
    }
}
