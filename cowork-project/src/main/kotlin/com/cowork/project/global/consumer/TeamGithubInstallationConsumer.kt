package com.cowork.project.global.consumer

import com.cowork.project.domain.github.entity.TeamGithubInstallation
import com.cowork.project.domain.github.repository.TeamGithubInstallationRepository
import com.cowork.project.domain.github.service.TeamGithubStateVerifier
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Component
class TeamGithubInstallationConsumer(
    private val teamGithubInstallationRepository: TeamGithubInstallationRepository,
    private val teamGithubStateVerifier: TeamGithubStateVerifier,
) {
    private val log = LoggerFactory.getLogger(TeamGithubInstallationConsumer::class.java)

    @KafkaListener(
        topics = [Topics.TEAM_GITHUB_CONNECTED],
        groupId = "cowork-project.team-github-connected",
        containerFactory = "teamGithubConnectedListenerContainerFactory",
    )
    @Transactional
    fun consumeConnected(payload: TeamGithubConnectedPayload) {
        val teamId = try {
            teamGithubStateVerifier.verifyState(payload.state)
        } catch (e: ExpectedException) {
            log.warn(
                "team.github.connected state 검증 실패로 이벤트를 무시합니다 [installationId={}, reason={}]",
                payload.installationId,
                e.message,
            )
            return
        }

        val ownedByOtherTeam = teamGithubInstallationRepository.findByInstallationId(payload.installationId)
            ?.takeIf { it.teamId != teamId }
        if (ownedByOtherTeam != null) {
            log.warn(
                "installation {}는 이미 다른 팀(teamId={})에 연결되어 있어 teamId={} 연결을 무시합니다",
                payload.installationId,
                ownedByOtherTeam.teamId,
                teamId,
            )
            return
        }

        val existing = teamGithubInstallationRepository.findById(teamId).orElse(null)
        if (existing != null) {
            existing.update(installationId = payload.installationId, orgLogin = payload.orgLogin)
        } else {
            teamGithubInstallationRepository.save(
                TeamGithubInstallation(
                    teamId = teamId,
                    installationId = payload.installationId,
                    orgLogin = payload.orgLogin,
                ),
            )
        }
        log.info("team.github.connected 처리 완료 [teamId={}, installationId={}]", teamId, payload.installationId)
    }

    @KafkaListener(
        topics = [Topics.TEAM_GITHUB_DISCONNECTED],
        groupId = "cowork-project.team-github-disconnected",
        containerFactory = "teamGithubDisconnectedListenerContainerFactory",
    )
    @Transactional
    fun consumeDisconnected(payload: TeamGithubDisconnectedPayload) {
        teamGithubInstallationRepository.findByInstallationId(payload.installationId)?.let {
            teamGithubInstallationRepository.delete(it)
        }
        log.info("team.github.disconnected 처리 완료 [installationId={}]", payload.installationId)
    }
}
