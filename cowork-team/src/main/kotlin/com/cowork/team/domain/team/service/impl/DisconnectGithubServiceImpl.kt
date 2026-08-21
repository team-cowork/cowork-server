package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.DisconnectGithubService
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.global.consumer.TeamGithubEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DisconnectGithubServiceImpl(
    private val teamRepository: TeamRepository,
    private val teamAccessGuard: TeamAccessGuard,
    private val teamGithubEventPublisher: TeamGithubEventPublisher,
) : DisconnectGithubService {

    @Transactional
    override fun execute(userId: Long, teamId: Long) {
        val team = teamAccessGuard.requireRole(teamId, userId, TeamRole.OWNER, TeamRole.ADMIN).team
        val installationId = team.githubInstallationId
        team.disconnectGithub()
        teamRepository.save(team)
        // cowork-project 등의 로컬 캐시도 함께 해제되도록 통지한다.
        installationId?.let { teamGithubEventPublisher.publishDisconnected(it) }
    }
}
