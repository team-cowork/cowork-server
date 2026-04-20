package com.cowork.preference.service

import com.cowork.preference.cache.PreferenceCache
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.domain.SettingSchema
import com.cowork.preference.messaging.PreferenceProducer
import com.cowork.preference.repository.PreferenceRepository
import io.vertx.core.json.JsonObject

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

        if (resourceType == ResourceType.ACCOUNT && filtered.containsKey("status")) {
            producer.publishStatusChanged(
                accountId = resourceId,
                previousStatus = previousStatus,
                newStatus = filtered.getString("status"),
                reason = "MANUAL",
            )
        }

        return Result.success(updated)
    }
}
