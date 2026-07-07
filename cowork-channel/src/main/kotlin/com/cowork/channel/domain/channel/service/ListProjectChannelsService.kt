package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse

interface ListProjectChannelsService {
    fun execute(userId: Long, projectId: Long): List<ChannelResponse>
}
