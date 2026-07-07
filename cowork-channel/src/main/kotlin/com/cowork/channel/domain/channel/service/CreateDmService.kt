package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse

interface CreateDmService {
    fun execute(userId: Long, targetUserId: Long): ChannelResponse
}
