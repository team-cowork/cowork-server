package com.cowork.team.global.consumer

import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.support.TeamGithubStateSupport
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

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
    @Transactional
    fun consumeConnected(payload: TeamGithubConnectedPayload) {
        val (teamId, _) = try {
            teamGithubStateSupport.verifyState(payload.state)
        } catch (e: ExpectedException) {
            log.warn("team.github.connected state 검증 실패로 이벤트를 무시합니다: {}", e.message)
            return
        }
        // 이미 다른 팀이 연결해둔 installation이면 탈취를 막기 위해 무시한다.
        val ownedByOtherTeam = teamRepository.findByGithubInstallationId(payload.installationId)
            ?.takeIf { it.id != teamId }
        if (ownedByOtherTeam != null) {
            log.warn(
                "installation {}는 이미 다른 팀(teamId={})에 연결되어 있어 teamId={} 연결을 무시합니다",
                payload.installationId,
                ownedByOtherTeam.id,
                teamId,
            )
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
