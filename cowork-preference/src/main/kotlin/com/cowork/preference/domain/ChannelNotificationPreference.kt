package com.cowork.preference.domain

import java.time.OffsetDateTime

data class ChannelNotificationPreference(
    val accountId: Long,
    val channelId: Long,
    val notification: Boolean,
    val stateOccurredAt: OffsetDateTime,
)
