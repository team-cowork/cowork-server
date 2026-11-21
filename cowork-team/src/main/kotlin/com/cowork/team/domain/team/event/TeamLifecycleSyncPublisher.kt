package com.cowork.team.domain.team.event

import com.cowork.team.domain.team.repository.TeamEventStateRepository
import com.cowork.team.domain.teamMember.repository.TeamMemberEventStateRepository
import com.cowork.team.global.projection.ProjectionSnapshotCompletionPublisher
import com.cowork.team.global.projection.ProjectionSnapshotLock
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Component
class TeamLifecycleSyncPublisher(
    private val teamEventStateRepository: TeamEventStateRepository,
    private val teamMemberEventStateRepository: TeamMemberEventStateRepository,
    private val teamEventPublisher: TeamEventPublisher,
    private val teamMemberEventPublisher: TeamMemberEventPublisher,
    private val projectionSnapshotCompletionPublisher: ProjectionSnapshotCompletionPublisher,
    private val projectionSnapshotLock: ProjectionSnapshotLock,
    platformTransactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(platformTransactionManager)

    @EventListener(ApplicationReadyEvent::class)
    fun publishAllSnapshots() {
        projectionSnapshotLock.tryRun(SNAPSHOT_LOCK_NAME) { publishAllInTransactions() }
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 300_000)
    fun republishAllSnapshots() {
        projectionSnapshotLock.tryRun(SNAPSHOT_LOCK_NAME) { publishAllInTransactions() }
    }

    private fun publishAllInTransactions() {
        publishTeamStates()
        publishMemberStates()
        projectionSnapshotCompletionPublisher.publishCompleted(SNAPSHOT_TOPICS)
    }

    private fun publishTeamStates() {
        var afterId = 0L
        while (true) {
            val nextAfterId = transaction.execute {
                val states = teamEventStateRepository.findSnapshotBatch(afterId, PageRequest.of(0, PAGE_SIZE))
                states.forEach(teamEventPublisher::publishSnapshot)
                states.lastOrNull()?.teamId
            } ?: break
            afterId = nextAfterId
        }
    }

    private fun publishMemberStates() {
        var afterTeamId = 0L
        var afterUserId = 0L
        while (true) {
            val nextCursor = transaction.execute {
                val states = teamMemberEventStateRepository.findSnapshotBatch(
                    afterTeamId,
                    afterUserId,
                    PageRequest.of(0, PAGE_SIZE),
                )
                states.forEach(teamMemberEventPublisher::publishSnapshot)
                states.lastOrNull()?.let { it.teamId to it.userId }
            } ?: break
            afterTeamId = nextCursor.first
            afterUserId = nextCursor.second
        }
    }

    private companion object {
        const val PAGE_SIZE = 500
        const val SNAPSHOT_LOCK_NAME = "publishAllTeamSnapshots"
        val SNAPSHOT_TOPICS = setOf(Topics.TEAM_LIFECYCLE, Topics.TEAM_MEMBER_EVENT)
    }
}
