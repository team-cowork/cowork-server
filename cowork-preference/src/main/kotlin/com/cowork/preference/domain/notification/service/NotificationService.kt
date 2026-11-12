package com.cowork.preference.domain.notification.service

import com.cowork.preference.domain.notification.event.NotificationEvents
import com.cowork.preference.domain.notification.model.ChannelNotificationSettings
import com.cowork.preference.domain.notification.repository.NotificationRepository
import com.cowork.preference.global.outbox.PreferenceOutboxRepository
import io.vertx.core.json.JsonObject

class NotificationService(
    private val repository: NotificationRepository,
    private val outboxRepository: PreferenceOutboxRepository,
) {

    suspend fun getNotification(accountId: Long, channelId: Long): JsonObject =
        ChannelNotificationSettings.normalize(repository.findNotification(accountId, channelId))

    suspend fun updateNotification(accountId: Long, channelId: Long, raw: JsonObject): JsonObject {
        val settings = ChannelNotificationSettings.fromRequest(raw)
        outboxRepository.inTransaction { connection ->
            val preference = repository.upsertNotification(connection, accountId, channelId, settings)
            outboxRepository.enqueue(connection, NotificationEvents.channelNotificationChanged(preference))
            preference
        }
        return settings
    }
}
