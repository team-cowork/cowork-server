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

        // ACCOUNT 상태 변경 시 이전 상태를 DB 쓰기 전에 미리 조회
        val previousStatus = if (resourceType == ResourceType.ACCOUNT && filtered.containsKey("status")) {
            runCatching { repository.findSettings(resourceId, resourceType)?.getString("status") }.getOrNull()
        } else null

        // DB 쓰기 후 RETURNING으로 병합된 최신 설정을 바로 반환
        val updated = repository.upsertSettings(resourceId, resourceType, filtered) ?: filtered
        cache.setSettings(resourceType, resourceId, updated)

        // Kafka 이벤트는 DB 쓰기 성공 후 발행
        if (resourceType == ResourceType.ACCOUNT) {
            handleAccountStatusChange(resourceId, filtered, previousStatus)
        }

        return Result.success(updated)
    }

    private suspend fun handleAccountStatusChange(
        accountId: Long,
        settings: JsonObject,
        previousStatus: String?,
    ) {
        val newStatus = settings.getString("status") ?: return
        val expiresAt = settings.getString("status_expires_at")

        producer.publishStatusChanged(
            accountId = accountId,
            previousStatus = previousStatus,
            newStatus = newStatus,
            reason = "MANUAL",
        )

        if (expiresAt != null) {
            // SettingSchema.validate에서 이미 ISO-8601 검증 완료
            val remainingSeconds = Instant.parse(expiresAt).epochSecond - Instant.now().epochSecond
            if (remainingSeconds > 0) cache.setStatusExpiry(accountId, remainingSeconds, newStatus)
        } else {
            cache.deleteStatusExpiry(accountId)
        }
    }
}
