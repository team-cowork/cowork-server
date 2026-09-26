package com.cowork.team.global.consumer

import com.cowork.team.domain.team.entity.TeamGithubInstallationRevision
import com.cowork.team.domain.team.event.TeamEventPublisher
import com.cowork.team.domain.team.repository.TeamGithubInstallationRevisionRepository
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
    private val teamEventPublisher: TeamEventPublisher,
    private val teamGithubInstallationRevisionRepository: TeamGithubInstallationRevisionRepository,
) {
    private val log = LoggerFactory.getLogger(TeamGithubInstallationConsumer::class.java)

    @KafkaListener(
        topics = [Topics.TEAM_GITHUB_CONNECTED],
        groupId = "cowork-team.github-connected",
        containerFactory = "teamGithubConnectedListenerContainerFactory",
    )
    @Transactional
    fun consumeConnected(payload: TeamGithubConnectedPayload) {
        if (!guardRevision(Topics.TEAM_GITHUB_CONNECTED, payload.installationId, payload.revision)) return

        val (teamId, actorUserId) = try {
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

        val team = teamRepository.findByIdForUpdate(teamId) ?: run {
            log.warn("team.github.connected: team {} 없음", teamId)
            return
        }

        // 이미 다른 installation이 연결된 팀이면, 명시적 해제 없이 조용히 교체되지 않도록 거부한다.
        val currentInstallationId = team.githubInstallationId
        if (currentInstallationId != null && currentInstallationId != payload.installationId) {
            log.warn(
                "team {}는 이미 installation {}에 연결되어 있어 installation {} 연결 요청을 무시합니다. " +
                    "먼저 연결 해제가 필요합니다.",
                teamId,
                currentInstallationId,
                payload.installationId,
            )
            return
        }
        if (currentInstallationId == payload.installationId && team.githubOrgLogin == payload.orgLogin) return

        team.connectGithub(payload.installationId, payload.orgLogin)
        teamRepository.save(team)
        teamEventPublisher.publishUpdated(team, actorUserId)
    }

    @KafkaListener(
        topics = [Topics.TEAM_GITHUB_DISCONNECTED],
        groupId = "cowork-team.github-disconnected",
        containerFactory = "teamGithubDisconnectedListenerContainerFactory",
    )
    @Transactional
    fun consumeDisconnected(payload: TeamGithubDisconnectedPayload) {
        if (!guardRevision(Topics.TEAM_GITHUB_DISCONNECTED, payload.installationId, payload.revision)) return

        val existing = teamRepository.findByGithubInstallationId(payload.installationId) ?: return
        val team = teamRepository.findByIdForUpdate(existing.id) ?: return
        if (team.githubInstallationId != payload.installationId) return
        team.disconnectGithub()
        teamRepository.save(team)
        teamEventPublisher.publishUpdated(team, team.ownerId)
    }

    // installation별 revision 원장을 잠근 상태로 조회한다. 같은 installation의 connected/disconnected
    // topic을 각각 소비하는 트랜잭션이 이 잠금으로 서로 순서를 기다리게 되어, 두 topic 사이의 도착 순서
    // 역전을 막는다. revision이 낮거나 같으면 false를 반환해 호출자가 나머지 로직을 건너뛰게 한다.
    private fun guardRevision(eventType: String, installationId: Long, revision: Long): Boolean {
        val ledger = teamGithubInstallationRevisionRepository.findByInstallationIdForUpdate(installationId)
            ?: teamGithubInstallationRevisionRepository.save(TeamGithubInstallationRevision.initial(installationId))

        if (revision <= ledger.revision) {
            log.warn(
                "{}: installation {} 의 revision {} 이(가) 이미 반영된 {} 보다 낮거나 같아 무시합니다",
                eventType,
                installationId,
                revision,
                ledger.revision,
            )
            return false
        }

        ledger.advanceTo(revision)
        teamGithubInstallationRevisionRepository.save(ledger)
        return true
    }
}
