package com.cowork.channel.domain.channel.event

import com.cowork.channel.domain.channel.repository.ChannelEventStateRepository
import com.cowork.channel.domain.channel.repository.DmParticipantEventStateRepository
import com.cowork.channel.global.projection.ProjectionReadinessState
import com.cowork.channel.global.projection.ProjectionSnapshotCompletionPublisher
import com.cowork.channel.global.projection.ProjectionSnapshotLock
import com.cowork.channel.global.projection.ProjectionStreams
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Component
class ChannelProjectionSnapshotPublisher(
    private val channelEventStateRepository: ChannelEventStateRepository,
    private val dmParticipantEventStateRepository: DmParticipantEventStateRepository,
    private val channelEventPublisher: ChannelEventPublisher,
    private val dmParticipantEventPublisher: DmParticipantEventPublisher,
    private val projectionReadinessState: ProjectionReadinessState,
    private val projectionStreams: ProjectionStreams,
    private val projectionSnapshotCompletionPublisher: ProjectionSnapshotCompletionPublisher,
    private val projectionSnapshotLock: ProjectionSnapshotLock,
    platformTransactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(platformTransactionManager)

    @EventListener(ApplicationReadyEvent::class)
    fun publishAllSnapshots() {
        projectionSnapshotLock.tryRun(SNAPSHOT_LOCK_NAME) { publishAllInTransactions() }
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    fun republishAllSnapshots() {
        projectionSnapshotLock.tryRun(SNAPSHOT_LOCK_NAME) { publishAllInTransactions() }
    }

    private fun publishAllInTransactions() {
        if (!projectionReadinessState.isCaughtUp(projectionStreams.channelStateUpstream)) return
        publishChannelStates()
        if (!projectionReadinessState.isCaughtUp(projectionStreams.channelStateUpstream)) return
        projectionSnapshotCompletionPublisher.publishCompleted(setOf(ChannelEventPublisher.TOPIC))
        publishDmParticipantStates()
        projectionSnapshotCompletionPublisher.publishCompleted(setOf(DmParticipantEventPublisher.TOPIC))
    }

    private fun publishChannelStates() {
        var afterId = 0L
        while (true) {
            val nextAfterId = transaction.execute {
                val states = channelEventStateRepository.findSnapshotBatch(afterId, PageRequest.of(0, PAGE_SIZE))
                states.forEach(channelEventPublisher::publishSnapshot)
                states.lastOrNull()?.channelId
            } ?: break
            afterId = nextAfterId
        }
    }

    private fun publishDmParticipantStates() {
        var afterChannelId = 0L
        var afterUserId = 0L
        while (true) {
            val nextCursor = transaction.execute {
                val states = dmParticipantEventStateRepository.findSnapshotBatch(
                    afterChannelId,
                    afterUserId,
                    PageRequest.of(0, PAGE_SIZE),
                )
                states.forEach(dmParticipantEventPublisher::publishSnapshot)
                states.lastOrNull()?.let { it.channelId to it.userId }
            } ?: break
            afterChannelId = nextCursor.first
            afterUserId = nextCursor.second
        }
    }

    private companion object {
        const val PAGE_SIZE = 500
        const val SNAPSHOT_LOCK_NAME = "publishAllChannelProjectionSnapshots"
    }
}
