package com.cowork.preference.service

import com.cowork.preference.cache.PreferenceCache
import com.cowork.preference.repository.NotificationRepository
import io.vertx.core.json.JsonObject

private val DEFAULT_NOTIFICATION = JsonObject().put("notification", true)

class NotificationService(
    private val repository: NotificationRepository,
    private val cache: PreferenceCache,
) {

    suspend fun getNotification(accountId: Long, channelId: Long): JsonObject {
        cache.getNotification(accountId, channelId)?.let { return it }
        val settings = repository.findNotification(accountId, channelId) ?: DEFAULT_NOTIFICATION
        cache.setNotification(accountId, channelId, settings)
        return settings
    }

    suspend fun updateNotification(accountId: Long, channelId: Long, raw: JsonObject): JsonObject {
        val enabled = raw.getBoolean("notification", true)
        val settings = JsonObject().put("notification", enabled)
        repository.upsertNotification(accountId, channelId, settings)
        cache.setNotification(accountId, channelId, settings)
        return settings
    }
}
