package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.presentation.data.request.CreateChannelRequest
import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse

interface CreateChannelService {
    fun createChannel(userId: Long, request: CreateChannelRequest): ChannelResponse
}
