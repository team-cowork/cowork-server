package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.presentation.data.response.ChannelMembershipResponse

interface VerifyChannelMembershipService {
    fun execute(channelId: Long, userId: Long): ChannelMembershipResponse
}
