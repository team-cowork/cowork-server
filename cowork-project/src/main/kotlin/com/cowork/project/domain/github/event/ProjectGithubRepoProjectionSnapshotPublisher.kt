package com.cowork.project.domain.github.event

import com.cowork.project.domain.github.repository.ProjectGithubRepoEventTombstoneRepository
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.global.projection.ProjectionReadinessState
import com.cowork.project.global.projection.ProjectionSnapshotCompletionPublisher
import com.cowork.project.global.projection.ProjectionSnapshotLock
import com.cowork.project.global.projection.ProjectionStreams
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.atomic.AtomicBoolean

@Component
class ProjectGithubRepoProjectionSnapshotPublisher(
    private val repository: ProjectGithubRepoRepository,
    private val tombstoneRepository: ProjectGithubRepoEventTombstoneRepository,
    private val eventPublisher: ProjectGithubRepoEventPublisher,
    private val completionPublisher: ProjectionSnapshotCompletionPublisher,
    private val projectionReadiness: ProjectionReadinessState,
    private val snapshotLock: ProjectionSnapshotLock,
    streams: ProjectionStreams,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)
    private val requiredUpstream = setOf(streams.teamMember, streams.teamLifecycle, streams.channelState)
    private val initialSnapshotPublished = AtomicBoolean(false)
    private val snapshotPublishing = AtomicBoolean(false)

    @Scheduled(initialDelay = INITIAL_RETRY_DELAY_MS, fixedDelay = INITIAL_RETRY_DELAY_MS)
    fun publishInitialSnapshotWhenReady() {
        if (initialSnapshotPublished.get()) return
        publishSnapshotIfReady(markInitialCompleted = true)
    }

    @Scheduled(initialDelay = REPUBLISH_INTERVAL_MS, fixedDelay = REPUBLISH_INTERVAL_MS)
    fun republishAllSnapshots() {
        if (!initialSnapshotPublished.get()) return
        publishSnapshotIfReady(markInitialCompleted = false)
    }

    private fun publishSnapshotIfReady(markInitialCompleted: Boolean) {
        if (!snapshotPublishing.compareAndSet(false, true)) return
        try {
            snapshotLock.tryRun(SNAPSHOT_LOCK_NAME) {
                if (!projectionReadiness.areCurrent(requiredUpstream)) return@tryRun
                publishAllSnapshots()
                if (!projectionReadiness.areCurrent(requiredUpstream)) return@tryRun
                completionPublisher.publishCompleted(setOf(PROJECT_GITHUB_REPO_EVENT_TOPIC))
                if (markInitialCompleted) initialSnapshotPublished.set(true)
            }
        } finally {
            snapshotPublishing.set(false)
        }
    }

    private fun publishAllSnapshots() {
        var afterId = 0L
        while (true) {
            val nextAfterId = transaction.execute {
                val repoLinks = repository.findSnapshotBatch(afterId, PageRequest.of(0, PAGE_SIZE))
                repoLinks.forEach(eventPublisher::publishSnapshot)
                repoLinks.lastOrNull()?.id
            } ?: break
            afterId = nextAfterId
        }
        publishTombstones()
    }

    private fun publishTombstones() {
        var afterRepoId = 0L
        while (true) {
            val nextAfterRepoId = transaction.execute {
                val tombstones = tombstoneRepository.findSnapshotBatch(afterRepoId, PageRequest.of(0, PAGE_SIZE))
                tombstones.forEach(eventPublisher::publishSnapshot)
                tombstones.lastOrNull()?.repoId
            } ?: break
            afterRepoId = nextAfterRepoId
        }
    }

    private companion object {
        const val PAGE_SIZE = 500
        const val INITIAL_RETRY_DELAY_MS = 1_000L
        const val REPUBLISH_INTERVAL_MS = 300_000L
        const val SNAPSHOT_LOCK_NAME = "cowork-project:github-repo-snapshot"
    }
}
