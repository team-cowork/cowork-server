package com.cowork.channel.domain.channel.presentation.data.response

import com.cowork.channel.domain.channel.entity.DmParticipant

data class ChannelAccessUserResponse(val channelId: Long, val userId: Long) {
    companion object {
        fun of(member: DmParticipant) = ChannelAccessUserResponse(member.channelId, member.userId)
    }
}
