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
        val members = channelMemberRepository.findAllByChannelIdForUpdateOrderByIdAsc(channelId)
        val requestedAt = Instant.now()
        val channelVersion = channelEventPublisher.publishDeleted(channel, requestedAt)
        members.forEach { member ->
            channelMemberEventPublisher.publishLeave(
                channel.id,
                member.userId,
                requestedAt = channelVersion,
            )
        }
        channelRepository.delete(channel)
    }
}
