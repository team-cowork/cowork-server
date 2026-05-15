package com.cowork.channel.dto

import com.cowork.channel.domain.ChannelMember
import java.time.LocalDateTime

data class ChannelMemberResponse(
    val id: Long,
    val channelId: Long,
    val userId: Long,
    val joinedAt: LocalDateTime,
) {
    companion object {
        fun of(member: ChannelMember) = ChannelMemberResponse(
            id = member.id,
            channelId = member.channelId,
            userId = member.userId,
            joinedAt = member.joinedAt,
        )
    }
}
