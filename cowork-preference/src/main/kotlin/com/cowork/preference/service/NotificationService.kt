package com.cowork.preference.service

import com.cowork.preference.domain.ChannelNotificationSettings
import com.cowork.preference.messaging.PreferenceEvents
import com.cowork.preference.repository.NotificationRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
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
            outboxRepository.enqueue(connection, PreferenceEvents.channelNotificationChanged(preference))
            preference
        }
        return settings
    }
}
