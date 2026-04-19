package com.cowork.preference.cache

import com.cowork.preference.domain.ResourceType
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.redis.client.RedisAPI

private const val TTL_SECONDS = 300L

class PreferenceCache(private val redis: RedisAPI) {

    suspend fun getSettings(resourceType: ResourceType, resourceId: Long): JsonObject? {
        val value = redis.get(settingKey(resourceType, resourceId)).coAwait()
        return value?.toString()?.let { JsonObject(it) }
    }

    suspend fun setSettings(resourceType: ResourceType, resourceId: Long, settings: JsonObject) {
        redis.setex(settingKey(resourceType, resourceId), TTL_SECONDS.toString(), settings.encode()).coAwait()
    }

    suspend fun invalidateSettings(resourceType: ResourceType, resourceId: Long) {
        redis.del(listOf(settingKey(resourceType, resourceId))).coAwait()
    }

    suspend fun getNotification(accountId: Long, channelId: Long): JsonObject? {
        val value = redis.get(notificationKey(accountId, channelId)).coAwait()
        return value?.toString()?.let { JsonObject(it) }
    }

    suspend fun setNotification(accountId: Long, channelId: Long, settings: JsonObject) {
        redis.setex(notificationKey(accountId, channelId), TTL_SECONDS.toString(), settings.encode()).coAwait()
    }

    suspend fun invalidateNotification(accountId: Long, channelId: Long) {
        redis.del(listOf(notificationKey(accountId, channelId))).coAwait()
    }

    suspend fun setStatusExpiry(accountId: Long, expiresInSeconds: Long, previousStatus: String) {
        redis.setex(statusExpireKey(accountId), expiresInSeconds.toString(), previousStatus).coAwait()
    }

    suspend fun getStatusExpiry(accountId: Long): String? {
        return redis.get(statusExpireKey(accountId)).coAwait()?.toString()
    }

    suspend fun deleteStatusExpiry(accountId: Long) {
        redis.del(listOf(statusExpireKey(accountId))).coAwait()
    }

    private fun settingKey(resourceType: ResourceType, resourceId: Long) =
        "pref:${resourceType.name}:$resourceId"

    private fun notificationKey(accountId: Long, channelId: Long) =
        "pref:notification:$accountId:$channelId"

    private fun statusExpireKey(accountId: Long) =
        "status:expire:$accountId"
}
