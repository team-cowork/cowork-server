package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.QueryChannelService
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryChannelServiceImpl(
    private val channelAccessGuard: ChannelAccessGuard,
    private val channelPermissionSupport: ChannelPermissionSupport,
) : QueryChannelService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, channelId: Long): ChannelResponse {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        channelPermissionSupport.requireChannelAccess(channel, userId)
        return ChannelResponse.of(channel)
    }
}
