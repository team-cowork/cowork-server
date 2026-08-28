package com.cowork.preference.service

import com.cowork.preference.cache.PreferenceCache
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.domain.SettingSchema
import com.cowork.preference.messaging.PreferenceEvents
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.PreferenceRepository
import io.vertx.core.json.JsonObject

class PreferenceService(
    private val repository: PreferenceRepository,
    private val cache: PreferenceCache,
    private val outboxRepository: PreferenceOutboxRepository,
) {

    suspend fun getSettings(resourceType: ResourceType, resourceId: Long): JsonObject {
        cache.getSettings(resourceType, resourceId)?.let { return normalizeSettings(resourceType, it) }
        val settings = normalizeSettings(resourceType, repository.findSettings(resourceId, resourceType))
        cache.setSettings(resourceType, resourceId, settings)
        return settings
    }

    suspend fun getSettingsBulk(resourceType: ResourceType, resourceIds: List<Long>): Map<Long, JsonObject> {
        if (resourceIds.isEmpty()) return emptyMap()
        val found = repository.findSettingsForResources(resourceIds, resourceType)
        val normalized = resourceIds.associateWith { normalizeSettings(resourceType, found[it]) }
        normalized.forEach { (id, settings) -> cache.setSettings(resourceType, id, settings) }
        return normalized
    }

    suspend fun updateSettings(resourceType: ResourceType, resourceId: Long, raw: JsonObject): Result<JsonObject> {
        val filtered = SettingSchema.filter(resourceType, raw)
        val validationTarget = validationTarget(resourceType, resourceId, filtered)
        val validationError = SettingSchema.validate(resourceType, validationTarget)
        if (validationError != null) return Result.failure(IllegalArgumentException(validationError))

        val needPrevStatus = resourceType == ResourceType.ACCOUNT && filtered.containsKey("status")
        val changedNicknameSettings = nicknameFormatSettings(filtered)
        val updated = outboxRepository.inTransaction { connection ->
            val result = repository.upsertSettings(
                client = connection,
                resourceId = resourceId,
                resourceType = resourceType,
                settings = filtered,
                fetchPreviousStatus = needPrevStatus,
            )
            if (needPrevStatus) {
                outboxRepository.enqueue(
                    connection,
                    PreferenceEvents.statusChanged(
                        accountId = resourceId,
                        previousStatus = result.previousStatus,
                        newStatus = filtered.getString("status"),
                        reason = "MANUAL",
                        occurredAt = result.updatedAt,
                    ),
                )
            }
            if (resourceType == ResourceType.TEAM && changedNicknameSettings.size() > 0) {
                outboxRepository.enqueue(
                    connection,
                    PreferenceEvents.teamSettingsChanged(
                        teamId = resourceId,
                        changedSettings = changedNicknameSettings,
                        settings = result.settings,
                        occurredAt = result.updatedAt,
                    ),
                )
            }
            if (resourceType == ResourceType.GITHUB_REPO) {
                outboxRepository.enqueue(
                    connection,
                    PreferenceEvents.githubRepoSettingState(
                        repoId = resourceId,
                        settings = result.settings,
                        occurredAt = result.stateOccurredAt,
                        snapshot = false,
                    ),
                )
            }
            normalizeSettings(resourceType, result.settings)
        }
        cache.setSettings(resourceType, resourceId, updated)

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

    private suspend fun validationTarget(
        resourceType: ResourceType,
        resourceId: Long,
        filtered: JsonObject,
    ): JsonObject {
        if (resourceType != ResourceType.TEAM || !containsNicknameFormatSettings(filtered)) return filtered
        val merged = getSettings(resourceType, resourceId).copy()
        filtered.forEach { entry -> merged.put(entry.key, entry.value) }
        return merged
    }

    private fun containsNicknameFormatSettings(settings: JsonObject): Boolean =
        settings.containsKey(NICKNAME_FORMAT_ENFORCED) || settings.containsKey(NICKNAME_FORMAT_EXAMPLE)

    private fun normalizeSettings(resourceType: ResourceType, settings: JsonObject?): JsonObject {
        if (resourceType != ResourceType.GITHUB_REPO) return settings ?: JsonObject()
        return JsonObject()
            .put("label_auto_apply", settings?.getBoolean("label_auto_apply", true) ?: true)
    }

    companion object {
        private const val NICKNAME_FORMAT_ENFORCED = "nickname_format_enforced"
        private const val NICKNAME_FORMAT_EXAMPLE = "nickname_format_example"
    }
}
