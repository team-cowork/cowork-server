package com.cowork.team.domain.teamInvite.service.impl

import com.cowork.team.domain.teamInvite.repository.TeamInviteRepository
import com.cowork.team.domain.teamInvite.service.DeleteInviteService
import com.cowork.team.domain.teamInvite.service.TeamInviteAccessGuard
import com.cowork.team.domain.teamRole.entity.TeamRole
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional
class DeleteInviteServiceImpl(
    private val teamInviteRepository: TeamInviteRepository,
    private val teamInviteAccessGuard: TeamInviteAccessGuard,
) : DeleteInviteService {

    override fun execute(userId: Long, teamId: Long, inviteCode: String) {
        val actor = teamInviteAccessGuard.requireMember(teamId, userId)
        val invite = teamInviteRepository.findByTeamIdAndInviteCode(teamId, inviteCode)
            ?: throw ExpectedException("초대 링크를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)

        if (invite.isDeleted()) {
            throw ExpectedException("초대 링크를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }

        val isOwnerOrAdmin = actor.role == TeamRole.OWNER || actor.role == TeamRole.ADMIN
        if (invite.createdBy != userId && !isOwnerOrAdmin) {
            throw ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)
        }

        invite.softDelete()
    }
}
