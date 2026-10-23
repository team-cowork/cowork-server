package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.GithubSyncMarker
import com.cowork.project.domain.github.entity.GithubSyncRequest
import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.event.GITHUB_SYNC_COMMAND_TOPIC
import com.cowork.project.domain.github.event.GithubSyncCommand
import com.cowork.project.domain.github.event.GithubSyncCommandPublisher
import com.cowork.project.domain.github.event.key
import com.cowork.project.domain.github.repository.GithubSyncMarkerRepository
import com.cowork.project.domain.github.repository.GithubSyncRequestRepository
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.github.repository.TeamGithubInstallationRepository
import com.cowork.project.global.outbox.OutboxWriter
import com.cowork.project.global.projection.ProjectionSnapshotLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Locale

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
    private val outboxWriter: OutboxWriter,
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

    /**
     * 프로젝트에 새로 연결한 repo는 다음 주기를 기다리지 않도록 연결 트랜잭션의 outbox로 바로 요청한다.
     * 요청 시각도 함께 저장하므로, 그 뒤의 주기는 쿨다운 동안 같은 repo를 다시 요청하지 않는다.
     */
    fun requestRepositorySync(ref: GithubRepoRef) {
        val now = Instant.now()
        val target = repositoryTarget(ref)
        val markers = markerRepository.findAllById(target.markerIds).associate { it.id to it.completedAt }
        val lastRequestedAt = requestRepository.findById(target.requestId).map { it.requestedAt }.orElse(null)
        if (!needsRequest(target.markerIds.map(markers::get), lastRequestedAt, now)) return

        val command = target.toCommand(now)
        outboxWriter.enqueue(GITHUB_SYNC_COMMAND_TOPIC, command.key, command)
        requestRepository.upsert(target.requestId, now)
    }

    internal fun needsRequest(markerCompletedAts: List<Instant?>, lastRequestedAt: Instant?, now: Instant): Boolean {
        if (lastRequestedAt != null && lastRequestedAt.isAfter(now.minusMillis(requestCooldownMs))) return false
        val staleBefore = now.minusMillis(staleAfterMs)
        return markerCompletedAts.any { it == null || it.isBefore(staleBefore) }
    }

    // owner/repo는 `project.github-repo.event`와 같은 규칙으로 소문자로 맞춘다. 연결 URL의 대소문자가 서로
    // 다르거나 GitHub 정식 이름과 달라도 같은 대상·마커로 판단해야 같은 repo를 반복 요청하지 않는다.
    private fun targets(): List<GithubSyncTarget> {
        val orgs = installationRepository.findAll()
            .map { GithubSyncTarget(it.orgLogin.lowercase(Locale.ROOT), null, it.installationId) }
            .distinctBy { it.owner }
        val repos = projectGithubRepoRepository.findAll()
            .mapNotNull { GithubRepoUrlParser.parse(it.githubRepoUrl) }
            .map(::repositoryTarget)
            .distinct()
        return orgs + repos
    }

    private fun repositoryTarget(ref: GithubRepoRef) =
        GithubSyncTarget(ref.owner.lowercase(Locale.ROOT), ref.repo.lowercase(Locale.ROOT), null)

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
