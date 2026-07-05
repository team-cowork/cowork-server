package com.cowork.channel.domain.channel.service

interface RemoveChannelMemberService {
    fun removeMember(userId: Long, channelId: Long, memberId: Long)
}
