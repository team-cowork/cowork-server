package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse

interface ReorderTeamChannelsService {
    fun execute(userId: Long, teamId: Long, orderedChannelIds: List<Long>): List<ChannelResponse>
}
