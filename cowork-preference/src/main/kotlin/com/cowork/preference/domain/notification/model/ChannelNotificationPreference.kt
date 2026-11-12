package com.cowork.preference.domain.notification.model

import java.time.Instant
import java.time.OffsetDateTime

/** `notificationLevel`이 null이면 채널에 등급을 정하지 않고 계정과 팀 수준을 따른다(`DEFAULT`). */
data class ChannelNotificationPreference(
    val accountId: Long,
    val channelId: Long,
    val notificationLevel: NotificationLevel?,
    val mutedUntil: Instant?,
    val stateOccurredAt: OffsetDateTime,
) {
    val notification: Boolean = notificationLevel != NotificationLevel.NONE
    val notificationLevelName: String = ChannelNotificationSettings.levelName(notificationLevel)
}
