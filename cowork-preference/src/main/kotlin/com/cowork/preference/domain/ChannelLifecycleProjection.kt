package com.cowork.preference.domain

import java.time.Instant

data class ChannelLifecycleProjection(
    val channelId: Long,
    val teamId: Long?,
    val deleted: Boolean,
    val sourceOccurredAt: Instant,
    /** null은 channel.event.v2로 아직 확인하지 못한 값이며 공개로 가정하지 않는다. */
    val isPrivate: Boolean? = null,
)
