package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse
import com.cowork.channel.domain.channel.service.ArchiveChannelService
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.support.ChannelArchivePermissionSupport
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException
import java.time.LocalDateTime

@Service
class ArchiveChannelServiceImpl(
    private val channelAccessGuard: ChannelAccessGuard,
    private val archivePermissionSupport: ChannelArchivePermissionSupport,
    private val channelEventPublisher: ChannelEventPublisher,
) : ArchiveChannelService {

    @Transactional
    override fun execute(userId: Long, channelId: Long): ChannelResponse {
        val channel = channelAccessGuard.findChannelForUpdateOrThrow(channelId)
        archivePermissionSupport.requireArchivePermission(channel, userId)
        if (channel.isArchived) {
            throw ExpectedException("이미 보관된 채널입니다.", HttpStatus.CONFLICT)
        }
        channel.archive(userId, LocalDateTime.now())
        channelEventPublisher.publishUpdated(channel)
        return ChannelResponse.of(channel)
    }
}
