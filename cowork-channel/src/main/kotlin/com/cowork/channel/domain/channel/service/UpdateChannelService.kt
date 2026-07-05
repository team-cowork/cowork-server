package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.presentation.data.request.UpdateChannelRequest
import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse

interface UpdateChannelService {
    fun updateChannel(
        userId: Long,
        channelId: Long,
        request: UpdateChannelRequest,
        updateProjectId: Boolean,
    ): ChannelResponse
}
