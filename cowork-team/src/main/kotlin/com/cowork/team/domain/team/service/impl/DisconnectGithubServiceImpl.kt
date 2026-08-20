package com.cowork.team.domain.team.service.impl

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
) : DisconnectGithubService {

    @Transactional
    override fun execute(userId: Long, teamId: Long) {
        val team = teamAccessGuard.requireRole(teamId, userId, TeamRole.OWNER, TeamRole.ADMIN).team
        team.disconnectGithub()
        teamRepository.save(team)
    }
}
