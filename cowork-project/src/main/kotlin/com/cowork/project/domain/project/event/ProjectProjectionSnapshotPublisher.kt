package com.cowork.project.domain.project.event

import com.cowork.project.domain.project.repository.ProjectEventTombstoneRepository
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.projectMember.event.ProjectMemberEventPublisher
import com.cowork.project.domain.projectMember.repository.ProjectMemberEventTombstoneRepository
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
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
class ProjectProjectionSnapshotPublisher(
    private val projectRepository: ProjectRepository,
    private val projectTombstoneRepository: ProjectEventTombstoneRepository,
    private val projectMemberRepository: ProjectMemberRepository,
    private val memberTombstoneRepository: ProjectMemberEventTombstoneRepository,
    private val projectEventPublisher: ProjectEventPublisher,
    private val projectMemberEventPublisher: ProjectMemberEventPublisher,
    private val completionPublisher: ProjectionSnapshotCompletionPublisher,
    private val projectionReadiness: ProjectionReadinessState,
    private val snapshotLock: ProjectionSnapshotLock,
    streams: ProjectionStreams,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)
    private val requiredUpstream = setOf(streams.teamMember, streams.teamLifecycle)
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
                publishAllInTransactions()
                if (!projectionReadiness.areCurrent(requiredUpstream)) return@tryRun
                completionPublisher.publishCompleted(SNAPSHOT_TOPICS)
                if (markInitialCompleted) initialSnapshotPublished.set(true)
            }
        } finally {
            snapshotPublishing.set(false)
        }
    }

    private fun publishAllInTransactions() {
        var afterId = 0L
        while (true) {
            val nextAfterId = transaction.execute {
                val projects = projectRepository.findSnapshotBatch(afterId, PageRequest.of(0, PAGE_SIZE))
                projects.forEach { project ->
                    projectEventPublisher.publishSnapshot(project)
                    projectMemberRepository.findSnapshotByProjectId(project.id)
                        .forEach(projectMemberEventPublisher::publishSnapshot)
                }
                projects.lastOrNull()?.id
            } ?: break
            afterId = nextAfterId
        }
        publishProjectTombstones()
        publishMemberTombstones()
    }

    private fun publishProjectTombstones() {
        var afterProjectId = 0L
        while (true) {
            val nextAfterProjectId = transaction.execute {
                val tombstones = projectTombstoneRepository.findSnapshotBatch(
                    afterProjectId,
                    PageRequest.of(0, PAGE_SIZE),
                )
                tombstones.forEach(projectEventPublisher::publishSnapshot)
                tombstones.lastOrNull()?.projectId
            } ?: break
            afterProjectId = nextAfterProjectId
        }
    }

    private fun publishMemberTombstones() {
        var afterId = 0L
        while (true) {
            val nextAfterId = transaction.execute {
                val tombstones = memberTombstoneRepository.findSnapshotBatch(afterId, PageRequest.of(0, PAGE_SIZE))
                tombstones.forEach(projectMemberEventPublisher::publishSnapshot)
                tombstones.lastOrNull()?.id
            } ?: break
            afterId = nextAfterId
        }
    }

    private companion object {
        const val PAGE_SIZE = 500
        const val INITIAL_RETRY_DELAY_MS = 1_000L
        const val REPUBLISH_INTERVAL_MS = 300_000L
        const val SNAPSHOT_LOCK_NAME = "cowork-project:project-snapshot"
        val SNAPSHOT_TOPICS = setOf("project.event", "project.member.event")
    }
}
