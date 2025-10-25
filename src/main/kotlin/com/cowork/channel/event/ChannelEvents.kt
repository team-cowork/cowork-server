package com.cowork.channel.event

data class ChannelDeletedEvent(
    val channelId: Long,
    val teamId: Long,
)
