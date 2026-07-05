package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.event.TeamEventPayload
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.DeleteTeamService
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.global.support.afterCommit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class DeleteTeamServiceImpl(
    private val teamRepository: TeamRepository,
    private val teamEventPublisher: TeamEventPublisher,
    private val teamAccessGuard: TeamAccessGuard,
) : DeleteTeamService {

    override fun deleteTeam(userId: Long, teamId: Long) {
        val team = teamAccessGuard.requireRole(teamId, userId, TeamRole.OWNER).team
        val payload = TeamEventPayload(
            eventType = "TEAM_DELETED",
            teamId = team.id,
            teamName = team.name,
            actorUserId = userId,
            targetUserIds = emptyList(),
        )
        teamRepository.delete(team)

        afterCommit { teamEventPublisher.publishLifecycle(payload) }
    }
}
