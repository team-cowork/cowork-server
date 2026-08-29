package com.cowork.channel.global.consumer

import java.time.Instant

data class ChannelRolePolicyChangedEvent(
    val schemaVersion: Int,
    val eventType: String,
    val teamId: Long,
    val channelId: Long,
    val roleId: Long,
    val permissions: Map<String, Boolean>? = null,
    val snapshot: Boolean,
    val occurredAt: Instant,
)
