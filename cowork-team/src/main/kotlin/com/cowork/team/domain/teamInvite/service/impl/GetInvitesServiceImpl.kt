package com.cowork.team.domain.teamInvite.service.impl

import com.cowork.team.domain.teamInvite.presentation.data.response.InviteResponse
import com.cowork.team.domain.teamInvite.repository.TeamInviteRepository
import com.cowork.team.domain.teamInvite.service.GetInvitesService
import com.cowork.team.domain.teamInvite.service.TeamInviteAccessGuard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetInvitesServiceImpl(
    private val teamInviteRepository: TeamInviteRepository,
    private val teamInviteAccessGuard: TeamInviteAccessGuard,
) : GetInvitesService {

    override fun execute(userId: Long, teamId: Long): List<InviteResponse> {
        teamInviteAccessGuard.requireMember(teamId, userId)
        return teamInviteRepository.findAllByTeamId(teamId).map { InviteResponse.of(it) }
    }
}
