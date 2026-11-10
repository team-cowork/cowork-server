package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse

interface SearchArchivedChannelsService {
    fun execute(userId: Long, teamId: Long, channelId: Long?, projectId: Long?, q: String?): List<ChannelResponse>
}
