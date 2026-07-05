package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse

interface SearchChannelsService {
    fun searchChannels(userId: Long, teamId: Long, q: String): List<ChannelResponse>
}
