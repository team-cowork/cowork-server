package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.event.ChannelMemberEventPublisher
import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.DeleteChannelService
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class DeleteChannelServiceImpl(
    private val channelRepository: ChannelRepository,
    private val channelMemberRepository: ChannelMemberRepository,
    private val channelMemberEventPublisher: ChannelMemberEventPublisher,
    private val channelEventPublisher: ChannelEventPublisher,
    private val channelAccessGuard: ChannelAccessGuard,
    private val channelPermissionSupport: ChannelPermissionSupport,
) : DeleteChannelService {

    @Transactional
    override fun execute(userId: Long, channelId: Long) {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        channelAccessGuard.requireTeamChannel(channel)
        channelPermissionSupport.requireChannelManager(channel, userId)
        val members = channelMemberRepository.findByChannelId(channelId)
        channelRepository.delete(channel)
        val occurredAt = Instant.now()
        members.forEach { member ->
            channelMemberEventPublisher.publishLeave(
                channel.id,
                channel.teamId,
                member.userId,
                channel.type.name,
                occurredAt = occurredAt,
            )
        }
        channelEventPublisher.publishDeleted(channel, occurredAt)
    }
}
