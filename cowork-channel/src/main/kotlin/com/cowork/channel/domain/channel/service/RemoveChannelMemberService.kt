package com.cowork.channel.domain.channel.service

interface RemoveChannelMemberService {
    fun execute(userId: Long, channelId: Long, memberId: Long)
}
