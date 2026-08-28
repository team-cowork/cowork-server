package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.DisconnectGithubService
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.teamRole.entity.TeamRole
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DisconnectGithubServiceImpl(
    private val teamRepository: TeamRepository,
    private val teamAccessGuard: TeamAccessGuard,
    private val teamEventPublisher: TeamEventPublisher,
) : DisconnectGithubService {

    @Transactional
    override fun execute(userId: Long, teamId: Long) {
        val team = teamAccessGuard.findTeamForUpdateOrThrow(teamId)
        teamAccessGuard.requireRole(teamId, userId, TeamRole.OWNER, TeamRole.ADMIN)
        if (team.githubInstallationId == null) return
        team.disconnectGithub()
        teamRepository.save(team)
        teamEventPublisher.publishUpdated(team, userId)
    }
}
