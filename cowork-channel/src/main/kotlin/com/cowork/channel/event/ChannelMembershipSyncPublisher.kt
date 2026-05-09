package com.cowork.channel.event

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelMember
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.repository.ChannelRepository
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class ChannelMembershipSyncPublisher(
    private val channelRepository: ChannelRepository,
    private val channelMemberRepository: ChannelMemberRepository,
    private val channelMemberEventPublisher: ChannelMemberEventPublisher,
) {

    fun publishChannelSnapshot(channel: Channel, members: List<ChannelMember>) {
        members.forEach { member ->
            channelMemberEventPublisher.publishJoin(
                channelId = channel.id,
                teamId = channel.teamId,
                userId = member.userId,
            )
        }
    }

    @EventListener(ApplicationReadyEvent::class)
    @Transactional(readOnly = true)
    fun publishAllSnapshots() {
        channelRepository.findAll().forEach { channel ->
            val members = channelMemberRepository.findByChannelId(channel.id)
            publishChannelSnapshot(channel, members)
        }
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 300_000)
    @Transactional(readOnly = true)
    fun republishAllSnapshots() {
        publishAllSnapshots()
    }
}
