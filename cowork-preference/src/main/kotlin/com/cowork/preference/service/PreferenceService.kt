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
        val validationTarget = validationTarget(resourceType, resourceId, filtered)
        val validationError = SettingSchema.validate(resourceType, validationTarget)
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
        if (resourceType == ResourceType.TEAM) {
            val changedNicknameSettings = nicknameFormatSettings(filtered)
            if (changedNicknameSettings.size() > 0) {
                producer.publishTeamSettingsChanged(
                    teamId = resourceId,
                    changedSettings = changedNicknameSettings,
                    settings = updated,
                )
            }
        }

        return Result.success(updated)
    }

    private fun nicknameFormatSettings(settings: JsonObject): JsonObject {
        val result = JsonObject()
        if (settings.containsKey(NICKNAME_FORMAT_ENFORCED)) {
            result.put(NICKNAME_FORMAT_ENFORCED, settings.getValue(NICKNAME_FORMAT_ENFORCED))
        }
        if (settings.containsKey(NICKNAME_FORMAT_EXAMPLE)) {
            result.put(NICKNAME_FORMAT_EXAMPLE, settings.getValue(NICKNAME_FORMAT_EXAMPLE))
        }
        return result
    }

    private suspend fun validationTarget(resourceType: ResourceType, resourceId: Long, filtered: JsonObject): JsonObject {
        if (resourceType != ResourceType.TEAM || !containsNicknameFormatSettings(filtered)) return filtered
        val merged = getSettings(resourceType, resourceId).copy()
        filtered.forEach { entry -> merged.put(entry.key, entry.value) }
        return merged
    }

    private fun containsNicknameFormatSettings(settings: JsonObject): Boolean =
        settings.containsKey(NICKNAME_FORMAT_ENFORCED) || settings.containsKey(NICKNAME_FORMAT_EXAMPLE)

    companion object {
        private const val NICKNAME_FORMAT_ENFORCED = "nickname_format_enforced"
        private const val NICKNAME_FORMAT_EXAMPLE = "nickname_format_example"
    }
}
