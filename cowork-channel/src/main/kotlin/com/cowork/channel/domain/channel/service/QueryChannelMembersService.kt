package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.presentation.data.response.ChannelMemberResponse

interface QueryChannelMembersService {
    fun getMembers(userId: Long, channelId: Long): List<ChannelMemberResponse>
}
