package com.cowork.preference.service

import com.cowork.preference.cache.PreferenceCache
import com.cowork.preference.cache.VersionedSettings
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.domain.SettingDefaultsResolver
import com.cowork.preference.domain.SettingSchema
import com.cowork.preference.messaging.PreferenceEvents
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.PreferenceRepository
import com.cowork.preference.repository.ResourceSettingState
import io.vertx.core.json.JsonObject
import java.time.Instant

class PreferenceService(
    private val repository: PreferenceRepository,
    private val cache: PreferenceCache,
    private val outboxRepository: PreferenceOutboxRepository,
) {

    suspend fun getSettings(resourceType: ResourceType, resourceId: Long): JsonObject {
        cache.getSettings(resourceType, resourceId)?.let { return normalizeSettings(resourceType, it) }
        val stored = versioned(resourceType, repository.findSettings(resourceId, resourceType))
        cache.populateSettings(resourceType, resourceId, stored)
        return stored.settings
    }

    suspend fun getSettingsBulk(resourceType: ResourceType, resourceIds: List<Long>): Map<Long, JsonObject> {
        if (resourceIds.isEmpty()) return emptyMap()
        val found = repository.findSettingsForResources(resourceIds, resourceType)
        val stored = resourceIds.associateWith { versioned(resourceType, found[it]) }
        cache.populateSettingsBulk(resourceType, stored)
        return stored.mapValues { it.value.settings }
    }

    suspend fun updateSettings(resourceType: ResourceType, resourceId: Long, raw: JsonObject): Result<JsonObject> {
        if (resourceType == ResourceType.TEXT_CHANNEL && raw.containsKey("webhook")) {
            return Result.failure(IllegalArgumentException("웹훅 설정은 웹훅 관리 API에서 변경해야 합니다."))
        }
        val filtered = SettingSchema.filter(resourceType, raw)
        val validationTarget = validationTarget(resourceType, resourceId, filtered)
        val validationError = SettingSchema.validate(resourceType, validationTarget)
        if (validationError != null) return Result.failure(IllegalArgumentException(validationError))

        val needPrevStatus = resourceType == ResourceType.ACCOUNT && filtered.containsKey("status")
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
            when (resourceType) {
                ResourceType.ACCOUNT -> outboxRepository.enqueue(
                    connection,
                    PreferenceEvents.accountSettingState(
                        accountId = resourceId,
                        settings = result.settings,
                        occurredAt = result.stateOccurredAt,
                        snapshot = false,
                    ),
                )
                ResourceType.TEAM -> outboxRepository.enqueue(
                    connection,
                    PreferenceEvents.teamSettingState(
                        teamId = resourceId,
                        settings = result.settings,
                        occurredAt = result.stateOccurredAt,
                        snapshot = false,
                    ),
                )
                ResourceType.TEXT_CHANNEL -> outboxRepository.enqueue(
                    connection,
                    PreferenceEvents.textChannelSettingState(
                        channelId = resourceId,
                        settings = result.settings,
                        occurredAt = result.stateOccurredAt,
                        snapshot = false,
                    ),
                )
                ResourceType.VOICE_CHANNEL -> outboxRepository.enqueue(
                    connection,
                    PreferenceEvents.voiceChannelSettingState(
                        channelId = resourceId,
                        settings = result.settings,
                        occurredAt = result.stateOccurredAt,
                        snapshot = false,
                    ),
                )
                else -> Unit
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
            VersionedSettings(normalizeSettings(resourceType, result.settings), result.stateOccurredAt)
        }
        cache.setSettings(resourceType, resourceId, updated)

        return Result.success(updated.settings)
    }

    /**
     * 만료된 계정 상태를 지우고 지운 결과를 그 version으로 캐시에 기록한다. 만료된 계정 id를 반환한다.
     * key를 지우면 만료 전 값을 읽은 조회가 빈 key를 다시 채울 수 있으므로 무효화하지 않는다.
     */
    suspend fun clearExpiredStatuses(): List<Long> {
        val clearedStates = outboxRepository.inTransaction { connection ->
            val lockedExpired = repository.findExpiredAccountStatuses(connection)
            if (lockedExpired.isEmpty()) return@inTransaction emptyList()
            val clearedStates = repository.clearExpiredStatuses(connection, lockedExpired.map { it.first })
            val occurredAt = Instant.now()
            outboxRepository.enqueueAll(
                connection,
                clearedStates.map { state ->
                    PreferenceEvents.accountSettingState(
                        accountId = state.resourceId,
                        settings = state.settings,
                        occurredAt = state.stateOccurredAt,
                        snapshot = false,
                    )
                },
            )
            outboxRepository.enqueueAll(
                connection,
                lockedExpired.map { (accountId, previousStatus) ->
                    PreferenceEvents.statusChanged(
                        accountId = accountId,
                        previousStatus = previousStatus,
                        newStatus = null,
                        reason = "EXPIRED",
                        occurredAt = occurredAt,
                    )
                },
            )
            clearedStates
        }
        clearedStates.forEach { state ->
            cache.setSettings(ResourceType.ACCOUNT, state.resourceId, versioned(ResourceType.ACCOUNT, state))
        }
        return clearedStates.map { it.resourceId }
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

    /** 행이 없으면 어떤 변경보다 오래된 version으로 기본값을 채워, 이후 변경의 기록을 막지 않게 한다. */
    private fun versioned(resourceType: ResourceType, state: ResourceSettingState?) =
        VersionedSettings(normalizeSettings(resourceType, state?.settings), state?.stateOccurredAt ?: Instant.EPOCH)

    private fun normalizeSettings(resourceType: ResourceType, settings: JsonObject?): JsonObject {
        if (resourceType == ResourceType.TEXT_CHANNEL) return (settings?.copy() ?: JsonObject()).apply { remove("webhook") }
        if (resourceType != ResourceType.GITHUB_REPO) return settings ?: JsonObject()
        val resolved = SettingDefaultsResolver.resolve(settings, GITHUB_REPO_DEFAULTS)
        return JsonObject()
            .put(LABEL_AUTO_APPLY, resolved.getBoolean(LABEL_AUTO_APPLY))
    }

    companion object {
        private const val NICKNAME_FORMAT_ENFORCED = "nickname_format_enforced"
        private const val NICKNAME_FORMAT_EXAMPLE = "nickname_format_example"
        private const val LABEL_AUTO_APPLY = "label_auto_apply"
        private val GITHUB_REPO_DEFAULTS: Map<String, Any> = mapOf(LABEL_AUTO_APPLY to true)
    }
}
