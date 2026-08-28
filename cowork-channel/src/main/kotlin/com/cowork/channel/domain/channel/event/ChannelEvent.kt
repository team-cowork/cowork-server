package com.cowork.channel.domain.channel.event

import java.time.Instant

data class ChannelEvent(
    val eventType: String,
    val channelId: Long,
    val teamId: Long?,
    val projectId: Long?,
    val name: String,
    val type: String,
    val viewType: String,
    val description: String?,
    val isPrivate: Boolean,
    val position: Int,
    val occurredAt: Instant,
    val snapshot: Boolean = false,
)
