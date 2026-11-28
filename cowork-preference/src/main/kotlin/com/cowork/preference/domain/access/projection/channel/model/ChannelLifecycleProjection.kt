package com.cowork.preference.domain.access.projection.channel.model

import java.time.Instant

data class ChannelLifecycleProjection(
    val channelId: Long,
    val teamId: Long?,
    val deleted: Boolean,
    val sourceOccurredAt: Instant,
    val isPrivate: Boolean,
    val channelType: String,
    /** 보관된 채널은 읽기 전용이므로 설정과 웹훅 변경을 거부한다. */
    val archived: Boolean = false,
)
