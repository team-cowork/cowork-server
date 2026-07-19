package com.cowork.channel.domain.channel.service

import com.cowork.channel.domain.channel.presentation.data.request.AddMemberRequest
import com.cowork.channel.domain.channel.presentation.data.response.ChannelMemberResponse

interface AddChannelMemberService {
    fun execute(userId: Long, channelId: Long, request: AddMemberRequest): ChannelMemberResponse
}
