package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse

interface ReorderTeamChannelsService {
    fun reorderTeamChannels(userId: Long, teamId: Long, orderedChannelIds: List<Long>): List<ChannelResponse>
}
