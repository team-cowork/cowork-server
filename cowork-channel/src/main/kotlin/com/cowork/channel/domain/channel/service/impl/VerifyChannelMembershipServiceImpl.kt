package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.presentation.data.response.ChannelMembershipResponse
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.VerifyChannelMembershipService
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class VerifyChannelMembershipServiceImpl(
    private val channelAccessGuard: ChannelAccessGuard,
    private val channelPermissionSupport: ChannelPermissionSupport,
) : VerifyChannelMembershipService {

    @Transactional(readOnly = true)
    override fun execute(channelId: Long, userId: Long): ChannelMembershipResponse {
        val channel = channelAccessGuard.findChannelOrThrow(channelId)
        channelPermissionSupport.requireChannelAccess(channel, userId)
        return ChannelMembershipResponse(teamId = channel.teamId)
    }
}
