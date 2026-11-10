package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse

interface UnarchiveChannelService {
    fun execute(userId: Long, channelId: Long): ChannelResponse
}
