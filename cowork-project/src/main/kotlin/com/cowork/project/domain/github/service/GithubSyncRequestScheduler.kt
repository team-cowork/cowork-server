package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.GithubSyncMarker
import com.cowork.project.domain.github.entity.GithubSyncRequest
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.event.GithubSyncCommand
import com.cowork.project.domain.github.event.GithubSyncCommandPublisher
import com.cowork.project.domain.github.repository.GithubSyncMarkerRepository
import com.cowork.project.domain.github.repository.GithubSyncRequestRepository
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.github.repository.TeamGithubInstallationRepository
import com.cowork.project.global.projection.ProjectionSnapshotLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/** 동기화 대상. org는 repo 목록, repo는 이슈·PR·라벨·댓글이며, 각자 확인할 완료 마커가 다르다. */
data class GithubSyncTarget(val owner: String, val repo: String?, val installationId: Long?) {
    val requestId: String get() = if (repo == null) "ORG_REPOSITORIES:$owner" else "REPOSITORY:$owner/$repo"

    val markerIds: List<String>
        get() = if (repo == null) {
            listOf(GithubSyncMarker.id(GithubSyncStream.REPO, owner, null))
        } else {
            REPOSITORY_STREAMS.map { GithubSyncMarker.id(it, owner, repo) }
        }

    fun toCommand(requestedAt: Instant) = GithubSyncCommand(
        target = if (repo == null) "ORG_REPOSITORIES" else "REPOSITORY",
        owner = owner,
        repo = repo,
        installationId = installationId,
        requestedAt = requestedAt,
    )

    private companion object {
        val REPOSITORY_STREAMS = listOf(
            GithubSyncStream.ISSUE,
            GithubSyncStream.PULL_REQUEST,
            GithubSyncStream.LABEL,
            GithubSyncStream.ISSUE_COMMENT,
        )
    }
}

/**
 * 팀이 설치한 org(repo 목록)와 프로젝트에 연결된 repo(이슈·PR·라벨·댓글)의 완료 마커를 주기적으로 확인해,
 * 마커가 없거나 오래된 대상만 `cowork-github`에 동기화를 요청한다. 요청한 뒤 쿨다운 동안은 다시 요청하지
 * 않으므로, `cowork-github`가 처리 중이면 기다리고 실패했거나 처리 전에 내려갔으면 쿨다운 뒤 다시 요청한다.
 */
@Component
class GithubSyncRequestScheduler(
    private val projectGithubRepoRepository: ProjectGithubRepoRepository,
    private val installationRepository: TeamGithubInstallationRepository,
    private val markerRepository: GithubSyncMarkerRepository,
    private val requestRepository: GithubSyncRequestRepository,
    private val publisher: GithubSyncCommandPublisher,
    private val snapshotLock: ProjectionSnapshotLock,
    @Value("\${github.sync.request-cooldown-ms:1800000}") private val requestCooldownMs: Long,
    @Value("\${github.sync.stale-after-ms:604800000}") private val staleAfterMs: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ponytail: 매 주기 연결 repo와 설치 org를 전부 읽는다. 대상이 수천 개를 넘으면 마커가 오래된 대상만 고르는 쿼리로 바꾼다.
    @Scheduled(
        initialDelayString = "\${github.sync.request-interval-ms:60000}",
        fixedDelayString = "\${github.sync.request-interval-ms:60000}",
    )
    fun requestSyncs() {
        snapshotLock.tryRun(LOCK_NAME) {
            val now = Instant.now()
            val targets = targets()
            val markers = markerRepository.findAllById(targets.flatMap { it.markerIds })
                .associate { it.id to it.completedAt }
            val requests = requestRepository.findAllById(targets.map { it.requestId })
                .associate { it.id to it.requestedAt }
            targets
                .filter { needsRequest(it.markerIds.map(markers::get), requests[it.requestId], now) }
                .forEach { request(it, now) }
        }
    }

    internal fun needsRequest(markerCompletedAts: List<Instant?>, lastRequestedAt: Instant?, now: Instant): Boolean {
        if (lastRequestedAt != null && lastRequestedAt.isAfter(now.minusMillis(requestCooldownMs))) return false
        val staleBefore = now.minusMillis(staleAfterMs)
        return markerCompletedAts.any { it == null || it.isBefore(staleBefore) }
    }

    private fun targets(): List<GithubSyncTarget> {
        val orgs = installationRepository.findAll()
            .distinctBy { it.orgLogin }
            .map { GithubSyncTarget(it.orgLogin, null, it.installationId) }
        val repos = projectGithubRepoRepository.findAll()
            .mapNotNull { GithubRepoUrlParser.parse(it.githubRepoUrl) }
            .distinct()
            .map { GithubSyncTarget(it.owner, it.repo, null) }
        return orgs + repos
    }

    private fun request(target: GithubSyncTarget, now: Instant) {
        try {
            publisher.publish(target.toCommand(now))
            requestRepository.save(GithubSyncRequest(target.requestId, now))
        } catch (ex: Exception) {
            log.warn("Failed to request GitHub sync [target={}]", target.requestId, ex)
        }
    }

    private companion object {
        const val LOCK_NAME = "cowork-project:github-sync-request"
    }
}
