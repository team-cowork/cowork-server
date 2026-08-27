package com.cowork.channel.domain.channel.event

import com.cowork.channel.domain.channel.repository.ChannelEventStateRepository
import com.cowork.channel.domain.channel.repository.ChannelMemberEventStateRepository
import com.cowork.channel.global.projection.ProjectionReadinessState
import com.cowork.channel.global.projection.ProjectionSnapshotCompletionPublisher
import com.cowork.channel.global.projection.ProjectionSnapshotLock
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Component
class ChannelProjectionSnapshotPublisher(
    private val channelStateRepository: ChannelEventStateRepository,
    private val memberStateRepository: ChannelMemberEventStateRepository,
    private val channelEventPublisher: ChannelEventPublisher,
    private val channelMemberEventPublisher: ChannelMemberEventPublisher,
    private val readinessState: ProjectionReadinessState,
    private val completionPublisher: ProjectionSnapshotCompletionPublisher,
    private val snapshotLock: ProjectionSnapshotLock,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    @EventListener(ApplicationReadyEvent::class)
    fun publishAllSnapshots() {
        snapshotLock.tryRun(SNAPSHOT_LOCK_NAME) { publishAllInTransactions() }
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    fun republishAllSnapshots() {
        snapshotLock.tryRun(SNAPSHOT_LOCK_NAME) { publishAllInTransactions() }
    }

    private fun publishAllInTransactions() {
        if (!readinessState.refresh()) return
        publishChannelStates()
        publishChannelMemberStates()
        if (!readinessState.refresh()) return
        completionPublisher.publishCompleted(SNAPSHOT_TOPICS)
    }

    private fun publishChannelStates() {
        var afterId = 0L
        while (true) {
            val nextAfterId = transaction.execute {
                val states = channelStateRepository.findSnapshotBatch(afterId, PageRequest.of(0, PAGE_SIZE))
                states.forEach(channelEventPublisher::publishSnapshot)
                states.lastOrNull()?.channelId
            } ?: break
            afterId = nextAfterId
        }
    }

    private fun publishChannelMemberStates() {
        var afterChannelId = 0L
        var afterUserId = 0L
        while (true) {
            val nextCursor = transaction.execute {
                val states = memberStateRepository.findSnapshotBatch(
                    afterChannelId,
                    afterUserId,
                    PageRequest.of(0, PAGE_SIZE),
                )
                states.forEach(channelMemberEventPublisher::publishSnapshot)
                states.lastOrNull()?.let { it.channelId to it.userId }
            } ?: break
            afterChannelId = nextCursor.first
            afterUserId = nextCursor.second
        }
    }

    private companion object {
        const val PAGE_SIZE = 500
        const val SNAPSHOT_LOCK_NAME = "publishAllChannelProjectionSnapshots"
        val SNAPSHOT_TOPICS = setOf("channel.event", "channel.member.event")
    }
}
