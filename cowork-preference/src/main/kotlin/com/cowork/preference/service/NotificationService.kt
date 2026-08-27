package com.cowork.preference.service

import com.cowork.preference.messaging.PreferenceEvents
import com.cowork.preference.repository.NotificationRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import io.vertx.core.json.JsonObject

private val DEFAULT_NOTIFICATION = JsonObject().put("notification", true)

class NotificationService(
    private val repository: NotificationRepository,
    private val outboxRepository: PreferenceOutboxRepository,
) {

    suspend fun getNotification(accountId: Long, channelId: Long): JsonObject =
        repository.findNotification(accountId, channelId) ?: DEFAULT_NOTIFICATION.copy()

    suspend fun updateNotification(accountId: Long, channelId: Long, raw: JsonObject): JsonObject {
        val enabled = raw.getBoolean("notification", true)
        val settings = JsonObject().put("notification", enabled)
        outboxRepository.inTransaction { connection ->
            val preference = repository.upsertNotification(connection, accountId, channelId, settings)
            outboxRepository.enqueue(
                connection,
                PreferenceEvents.channelNotificationChanged(
                    accountId = preference.accountId,
                    channelId = preference.channelId,
                    notification = preference.notification,
                    occurredAt = preference.stateOccurredAt.toInstant(),
                ),
            )
            preference
        }
        return settings
    }
}
