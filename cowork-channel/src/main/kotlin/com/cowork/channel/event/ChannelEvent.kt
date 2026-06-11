package com.cowork.channel.event

data class ChannelEvent(
    val eventType: String,
    val channelId: Long,
    val teamId: Long?,
    val name: String,
    val type: String,
    val viewType: String,
    val description: String?,
    val isPrivate: Boolean,
)
