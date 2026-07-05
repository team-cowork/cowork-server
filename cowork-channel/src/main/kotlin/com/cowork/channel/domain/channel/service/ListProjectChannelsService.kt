package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse

interface ListProjectChannelsService {
    fun listProjectChannels(userId: Long, projectId: Long): List<ChannelResponse>
}
