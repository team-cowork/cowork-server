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

        val needPrevStatus = resourceType == ResourceType.ACCOUNT && filtered.containsKey("status")
        val (updatedSettings, previousStatus) = repository.upsertSettings(resourceId, resourceType, filtered, fetchPreviousStatus = needPrevStatus)
        val updated = updatedSettings ?: filtered
        cache.setSettings(resourceType, resourceId, updated)

        if (resourceType == ResourceType.ACCOUNT) {
            handleAccountStatusChange(resourceId, filtered, updated, previousStatus)
        }

        return Result.success(updated)
    }

    private suspend fun handleAccountStatusChange(
        accountId: Long,
        settings: JsonObject,
        updatedSettings: JsonObject,
        previousStatus: String?,
    ) {
        val newStatus = settings.getString("status")
        val expiresAt = settings.getString("status_expires_at")

        if (newStatus == null && expiresAt == null) return

        if (newStatus != null) {
            producer.publishStatusChanged(
                accountId = accountId,
                previousStatus = previousStatus,
                newStatus = newStatus,
                reason = "MANUAL",
            )
        }

        if (expiresAt != null) {
            val currentStatus = newStatus ?: updatedSettings.getString("status") ?: return
            val expireAtEpoch = Instant.parse(expiresAt).epochSecond
            val remainingSeconds = expireAtEpoch - Instant.now().epochSecond
            if (remainingSeconds > 0) cache.setStatusExpiry(accountId, remainingSeconds, expireAtEpoch, currentStatus)
        } else if (newStatus != null) {
            cache.deleteStatusExpiry(accountId)
        }
    }
}
