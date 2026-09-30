package com.cowork.preference.domain

import java.time.Instant

data class ChannelLifecycleProjection(
    val channelId: Long,
    val teamId: Long?,
    val deleted: Boolean,
    val sourceOccurredAt: Instant,
)
