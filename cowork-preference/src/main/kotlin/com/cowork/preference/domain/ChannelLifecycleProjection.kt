package com.cowork.preference.domain

import java.time.Instant

data class ChannelLifecycleProjection(
    val channelId: Long,
    val teamId: Long?,
    val deleted: Boolean,
    val sourceOccurredAt: Instant,
    /** null은 channel.event.v2로 아직 확인하지 못한 값이며 비공개로 취급한다. */
    val isPrivate: Boolean? = null,
)
