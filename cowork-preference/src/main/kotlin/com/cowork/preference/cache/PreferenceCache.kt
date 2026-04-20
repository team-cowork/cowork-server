package com.cowork.preference.cache

import com.cowork.preference.domain.ResourceType
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.redis.client.RedisAPI

private const val TTL_SECONDS = 300L
private const val EXPIRY_LOCK_KEY = "status:expiry:lock"
private const val LOCK_TTL_SECONDS = 55L

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

    suspend fun acquireExpiryLock(): Boolean {
        val result = redis.set(listOf(EXPIRY_LOCK_KEY, "1", "NX", "EX", LOCK_TTL_SECONDS.toString())).coAwait()
        return result?.toString() == "OK"
    }

    private fun settingKey(resourceType: ResourceType, resourceId: Long) =
        "pref:${resourceType.name}:$resourceId"

    private fun notificationKey(accountId: Long, channelId: Long) =
        "pref:notification:$accountId:$channelId"
}
