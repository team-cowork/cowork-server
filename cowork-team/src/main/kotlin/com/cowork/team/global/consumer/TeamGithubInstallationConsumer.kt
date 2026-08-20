package com.cowork.team.global.consumer

import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.support.TeamGithubStateSupport
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class TeamGithubInstallationConsumer(
    private val teamRepository: TeamRepository,
    private val teamGithubStateSupport: TeamGithubStateSupport,
) {
    private val log = LoggerFactory.getLogger(TeamGithubInstallationConsumer::class.java)

    @KafkaListener(
        topics = [Topics.TEAM_GITHUB_CONNECTED],
        groupId = "cowork-team.github-connected",
        containerFactory = "teamGithubConnectedListenerContainerFactory",
    )
    fun consumeConnected(payload: TeamGithubConnectedPayload) {
        val (teamId, _) = try {
            teamGithubStateSupport.verifyState(payload.state)
        } catch (e: Exception) {
            log.warn("잘못된 state, 무시: {}", e.message)
            return
        }
        val team = teamRepository.findById(teamId).orElse(null) ?: run {
            log.warn("team.github.connected: team {} 없음", teamId)
            return
        }
        team.connectGithub(payload.installationId, payload.orgLogin)
        teamRepository.save(team)
    }

    @KafkaListener(
        topics = [Topics.TEAM_GITHUB_DISCONNECTED],
        groupId = "cowork-team.github-disconnected",
        containerFactory = "teamGithubDisconnectedListenerContainerFactory",
    )
    fun consumeDisconnected(payload: TeamGithubDisconnectedPayload) {
        val team = teamRepository.findByGithubInstallationId(payload.installationId) ?: return
        team.disconnectGithub()
        teamRepository.save(team)
    }
}
