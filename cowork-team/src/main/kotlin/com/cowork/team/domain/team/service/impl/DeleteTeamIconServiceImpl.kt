package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.service.DeleteTeamIconService
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.team.service.TeamIconOwnershipManager
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class DeleteTeamIconServiceImpl(
    private val teamIconOwnershipManager: TeamIconOwnershipManager,
    private val teamAccessGuard: TeamAccessGuard,
    private val teamEventPublisher: TeamEventPublisher,
) : DeleteTeamIconService {

    @Transactional
    override fun execute(userId: Long, teamId: Long) {
        val team = teamAccessGuard.findTeamForUpdateOrThrow(teamId)
        teamAccessGuard.requirePermission(teamId, userId, "manage_team")
        team.iconUrl
            ?: throw ExpectedException("아이콘이 없습니다.", HttpStatus.NOT_FOUND)
        teamIconOwnershipManager.clear(team)
        teamEventPublisher.publishUpdated(team, userId)
    }
}
