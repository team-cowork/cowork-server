package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.presentation.data.response.ChannelAccessUserResponse

interface QueryChannelMembersService {
    fun execute(userId: Long, channelId: Long): List<ChannelAccessUserResponse>
}
