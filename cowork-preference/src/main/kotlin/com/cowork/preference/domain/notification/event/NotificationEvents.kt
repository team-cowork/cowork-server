package com.cowork.preference.domain.notification.event

import com.cowork.preference.domain.notification.model.ChannelNotificationPreference
import com.cowork.preference.global.messaging.event.PreferenceEvent
import com.cowork.preference.global.messaging.event.PreferenceEventPayload.basePayload
import com.cowork.preference.global.messaging.event.PreferenceTopics

object NotificationEvents {
    fun channelNotificationChanged(preference: ChannelNotificationPreference): PreferenceEvent {
        val occurredAt = preference.stateOccurredAt.toInstant()
        return PreferenceEvent(
            topic = PreferenceTopics.CHANNEL_NOTIFICATION_TOPIC,
            key = "${preference.accountId}:${preference.channelId}",
            payload = basePayload("UPSERT", occurredAt)
                .put("accountId", preference.accountId)
                .put("channelId", preference.channelId)
                .put("notification", preference.notification)
                .put("notificationLevel", preference.notificationLevelName)
                .put("mutedUntil", preference.mutedUntil?.toString()),
            occurredAt = occurredAt,
        )
    }
}
