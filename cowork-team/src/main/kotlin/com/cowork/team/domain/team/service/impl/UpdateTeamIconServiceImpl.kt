package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.presentation.data.response.IconConfirmResponse
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.team.service.TeamIconOwnershipService
import com.cowork.team.domain.team.service.UpdateTeamIconService
import com.cowork.team.domain.teamRole.entity.TeamRole
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UpdateTeamIconServiceImpl(
    private val iconOwnership: TeamIconOwnershipService,
    private val teamAccessGuard: TeamAccessGuard,
    private val teamEventPublisher: TeamEventPublisher,
) : UpdateTeamIconService {

    @Transactional
    override fun execute(userId: Long, teamId: Long, iconUrl: String): IconConfirmResponse {
        val team = teamAccessGuard.findTeamForUpdateOrThrow(teamId)
        teamAccessGuard.requireRole(teamId, userId, TeamRole.OWNER, TeamRole.ADMIN)
        val previousIconUrl = team.iconUrl

        if (previousIconUrl != iconUrl) {
            iconOwnership.replace(userId, team, iconUrl)
            teamEventPublisher.publishUpdated(team, userId)
        }
        return IconConfirmResponse(iconUrl = iconUrl)
    }
}
