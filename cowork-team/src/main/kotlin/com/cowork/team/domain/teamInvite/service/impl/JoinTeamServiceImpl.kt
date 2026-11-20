package com.cowork.team.domain.teamInvite.service.impl

import com.cowork.team.domain.teamInvite.presentation.data.response.JoinTeamResponse
import com.cowork.team.domain.teamInvite.repository.TeamInviteRepository
import com.cowork.team.domain.teamInvite.service.JoinTeamService
import com.cowork.team.domain.teamMember.RegisterTeamMemberService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class JoinTeamServiceImpl(
    private val teamInviteRepository: TeamInviteRepository,
    private val registerTeamMemberService: RegisterTeamMemberService,
) : JoinTeamService {

    @Transactional
    override fun execute(userId: Long, inviteCode: String): JoinTeamResponse {
        val invite = teamInviteRepository.findActiveByInviteCode(inviteCode)
            ?: throw ExpectedException("유효하지 않은 초대 코드입니다.", HttpStatus.NOT_FOUND)

        if (invite.isExpired()) {
            throw ExpectedException("만료된 초대 링크입니다.", HttpStatus.GONE)
        }

        val member = registerTeamMemberService.execute(invite.team.id, userId)
        return JoinTeamResponse.of(member)
    }
}
