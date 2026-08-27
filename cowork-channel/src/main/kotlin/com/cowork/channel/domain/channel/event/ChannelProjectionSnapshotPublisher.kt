package com.cowork.channel.domain.channel.event

import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.global.projection.ProjectionSnapshotCompletionPublisher
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockingTaskExecutor
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant

@Component
class ChannelProjectionSnapshotPublisher(
    private val channelRepository: ChannelRepository,
    private val channelMemberRepository: ChannelMemberRepository,
    private val channelEventPublisher: ChannelEventPublisher,
    private val channelMemberEventPublisher: ChannelMemberEventPublisher,
    private val completionPublisher: ProjectionSnapshotCompletionPublisher,
    private val lockingTaskExecutor: LockingTaskExecutor,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    @EventListener(ApplicationReadyEvent::class)
    fun publishAllSnapshots() {
        val lockConfig = LockConfiguration(
            Instant.now(),
            "publishAllChannelProjectionSnapshots",
            Duration.ofMinutes(10),
            Duration.ZERO,
        )
        lockingTaskExecutor.executeWithLock(Runnable { publishAllInTransactions() }, lockConfig)
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    @SchedulerLock(name = "republishAllChannelProjectionSnapshots", lockAtMostFor = "PT10M")
    fun republishAllSnapshots() {
        publishAllInTransactions()
    }

    private fun publishAllInTransactions() {
        var afterId = 0L
        while (true) {
            val nextAfterId = transaction.execute {
                val channels = channelRepository.findSnapshotBatch(afterId, PageRequest.of(0, PAGE_SIZE))
                channels.forEach { channel ->
                    channelEventPublisher.publishSnapshot(channel)
                    channelMemberRepository.findSnapshotByChannelId(channel.id)
                        .forEach { channelMemberEventPublisher.publishSnapshot(channel, it) }
                }
                channels.lastOrNull()?.id
            } ?: break
            afterId = nextAfterId
        }
        completionPublisher.publishCompleted(SNAPSHOT_TOPICS)
    }

    private companion object {
        const val PAGE_SIZE = 500
        val SNAPSHOT_TOPICS = setOf("channel.event", "channel.member.event")
    }
}
