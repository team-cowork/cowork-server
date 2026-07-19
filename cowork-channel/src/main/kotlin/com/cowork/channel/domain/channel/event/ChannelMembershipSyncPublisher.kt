package com.cowork.channel.domain.channel.event

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelMember
import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
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
class ChannelMembershipSyncPublisher(
    private val channelRepository: ChannelRepository,
    private val channelMemberRepository: ChannelMemberRepository,
    private val channelMemberEventPublisher: ChannelMemberEventPublisher,
    private val lockingTaskExecutor: LockingTaskExecutor,
    transactionManager: PlatformTransactionManager,
) {
    private val readOnlyTransaction = TransactionTemplate(transactionManager).apply {
        isReadOnly = true
    }

    fun publishChannelSnapshot(channel: Channel, members: List<ChannelMember>) {
        members.forEach { member ->
            channelMemberEventPublisher.publishJoin(
                channelId = channel.id,
                teamId = channel.teamId,
                userId = member.userId,
                channelType = channel.type.name,
            )
        }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun publishAllSnapshots() {
        val lockConfig = LockConfiguration(
            Instant.now(),
            "publishAllChannelSnapshots",
            Duration.ofMinutes(10),
            Duration.ZERO,
        )
        lockingTaskExecutor.executeWithLock(Runnable { publishAllInReadOnlyTransaction() }, lockConfig)
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 300_000)
    @SchedulerLock(name = "republishAllChannelSnapshots", lockAtMostFor = "PT10M")
    fun republishAllSnapshots() {
        publishAllInReadOnlyTransaction()
    }

    private fun publishAllInReadOnlyTransaction() {
        readOnlyTransaction.executeWithoutResult { doPublishAll() }
    }

    private fun doPublishAll() {
        var page = 0
        do {
            val membersPage = channelMemberRepository.findAll(PageRequest.of(page++, 500))
            val channelIds = membersPage.content.map { it.channelId }.distinct()
            val channels = channelRepository.findAllById(channelIds).associateBy { it.id }

            membersPage.content.forEach { member ->
                channels[member.channelId]?.let {
                    channelMemberEventPublisher.publishJoin(it.id, it.teamId, member.userId, it.type.name)
                }
            }
        } while (membersPage.hasNext())
    }
}
