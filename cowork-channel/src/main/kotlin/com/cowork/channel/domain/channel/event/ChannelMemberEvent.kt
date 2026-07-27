package com.cowork.channel.domain.channel.event

import java.time.LocalDateTime

enum class ChannelMemberEventType { JOIN, LEAVE }

data class ChannelMemberEvent(
    val eventType: ChannelMemberEventType,
    val channelId: Long,
    val teamId: Long?,
    val userId: Long,
    val role: String,
    val channelType: String,
    val occurredAt: LocalDateTime = LocalDateTime.now(),
)
