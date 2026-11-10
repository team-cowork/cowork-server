package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.UnarchiveChannelService
import com.cowork.channel.domain.channel.service.support.ChannelArchivePermissionSupport
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class UnarchiveChannelServiceImpl(
    private val channelAccessGuard: ChannelAccessGuard,
    private val archivePermissionSupport: ChannelArchivePermissionSupport,
    private val channelEventPublisher: ChannelEventPublisher,
) : UnarchiveChannelService {

    @Transactional
    override fun execute(userId: Long, channelId: Long): ChannelResponse {
        val channel = channelAccessGuard.findChannelForUpdateOrThrow(channelId)
        archivePermissionSupport.requireArchivePermission(channel, userId)
        if (!channel.isArchived) {
            throw ExpectedException("보관된 채널이 아닙니다.", HttpStatus.CONFLICT)
        }
        channel.unarchive()
        channelEventPublisher.publishUpdated(channel)
        return ChannelResponse.of(channel)
    }
}
