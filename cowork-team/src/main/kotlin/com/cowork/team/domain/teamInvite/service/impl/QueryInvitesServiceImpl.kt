package com.cowork.team.domain.teamInvite.service.impl

import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.teamInvite.presentation.data.response.InviteResponse
import com.cowork.team.domain.teamInvite.repository.TeamInviteRepository
import com.cowork.team.domain.teamInvite.service.QueryInvitesService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryInvitesServiceImpl(
    private val teamInviteRepository: TeamInviteRepository,
    private val teamAccessGuard: TeamAccessGuard,
) : QueryInvitesService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, teamId: Long): List<InviteResponse> {
        teamAccessGuard.requirePermission(teamId, userId, "manage_invites")
        return teamInviteRepository.findAllByTeamId(teamId).map { InviteResponse.of(it) }
    }
}
