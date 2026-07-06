package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse

interface SearchChannelsService {
    fun execute(userId: Long, teamId: Long, q: String): List<ChannelResponse>
}
