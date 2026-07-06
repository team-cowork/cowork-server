package com.cowork.team.domain.teamInvite.service.impl

import com.cowork.team.domain.team.event.TeamEventPayload
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.presentation.data.response.JoinTeamResponse
import com.cowork.team.domain.teamInvite.repository.TeamInviteRepository
import com.cowork.team.domain.teamInvite.service.JoinTeamService
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.global.support.afterCommit
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional
class JoinTeamServiceImpl(
    private val teamInviteRepository: TeamInviteRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val teamEventPublisher: TeamEventPublisher,
) : JoinTeamService {

    override fun execute(userId: Long, inviteCode: String): JoinTeamResponse {
        val invite = teamInviteRepository.findActiveByInviteCode(inviteCode)
            ?: throw ExpectedException("유효하지 않은 초대 코드입니다.", HttpStatus.NOT_FOUND)

        if (invite.isExpired()) {
            throw ExpectedException("만료된 초대 링크입니다.", HttpStatus.GONE)
        }

        val teamId = invite.team.id
        if (teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw ExpectedException("이미 팀 멤버입니다.", HttpStatus.CONFLICT)
        }

        val member = teamMemberRepository.save(TeamMember(team = invite.team, userId = userId))

        val payload = TeamEventPayload(
            eventType = "MEMBER_JOINED",
            teamId = teamId,
            teamName = invite.team.name,
            actorUserId = userId,
            targetUserIds = listOf(userId),
        )
        afterCommit { teamEventPublisher.publishLifecycle(payload) }

        return JoinTeamResponse(
            teamId = teamId,
            userId = userId,
            role = member.role.name,
            joinedAt = requireNotNull(member.joinedAt),
        )
    }
}
