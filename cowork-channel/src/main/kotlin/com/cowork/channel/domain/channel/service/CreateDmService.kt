package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse

interface CreateDmService {
    fun openDm(userId: Long, targetUserId: Long): ChannelResponse
}
