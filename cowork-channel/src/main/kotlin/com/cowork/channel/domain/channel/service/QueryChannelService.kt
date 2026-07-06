package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse

interface QueryChannelService {
    fun getChannel(userId: Long, channelId: Long): ChannelResponse
}
