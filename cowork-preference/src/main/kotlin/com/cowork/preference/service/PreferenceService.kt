package com.cowork.preference.service

import com.cowork.preference.cache.PreferenceCache
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.domain.SettingSchema
import com.cowork.preference.messaging.PreferenceProducer
import com.cowork.preference.repository.PreferenceRepository
import io.vertx.core.json.JsonObject
import java.time.Instant

class PreferenceService(
    private val repository: PreferenceRepository,
    private val cache: PreferenceCache,
    private val producer: PreferenceProducer,
) {

    suspend fun getSettings(resourceType: ResourceType, resourceId: Long): JsonObject {
        cache.getSettings(resourceType, resourceId)?.let { return it }
        val settings = repository.findSettings(resourceId, resourceType) ?: JsonObject()
        cache.setSettings(resourceType, resourceId, settings)
        return settings
    }

    suspend fun updateSettings(resourceType: ResourceType, resourceId: Long, raw: JsonObject): Result<JsonObject> {
        val filtered = SettingSchema.filter(resourceType, raw)
        val validationError = SettingSchema.validate(resourceType, filtered)
        if (validationError != null) return Result.failure(IllegalArgumentException(validationError))

        if (resourceType == ResourceType.ACCOUNT) {
            handleAccountStatusChange(resourceId, filtered)
        }

        repository.upsertSettings(resourceId, resourceType, filtered)
        val updated = repository.findSettings(resourceId, resourceType) ?: filtered
        cache.setSettings(resourceType, resourceId, updated)
        return Result.success(updated)
    }

    private suspend fun handleAccountStatusChange(accountId: Long, settings: JsonObject) {
        val newStatus = settings.getString("status") ?: return
        val expiresAt = settings.getString("status_expires_at")

        val previousStatus = try {
            val current = repository.findSettings(accountId, ResourceType.ACCOUNT)
            current?.getString("status")
        } catch (_: Exception) { null }

        producer.publishStatusChanged(
            accountId = accountId,
            previousStatus = previousStatus,
            newStatus = newStatus,
            reason = "MANUAL",
        )

        if (expiresAt != null) {
            val expireInstant = Instant.parse(expiresAt)
            val remainingSeconds = expireInstant.epochSecond - Instant.now().epochSecond
            if (remainingSeconds > 0) {
                cache.setStatusExpiry(accountId, remainingSeconds, newStatus)
            }
        } else {
            cache.deleteStatusExpiry(accountId)
        }
    }
}
