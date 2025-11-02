package com.cowork.preference.service

import com.cowork.global.error.BusinessException
import com.cowork.preference.domain.ResourceSetting
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.domain.SettingSchema
import com.cowork.preference.dto.SettingsResponse
import com.cowork.preference.event.AccountStatusChangedEvent
import com.cowork.preference.repository.ResourceSettingRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PreferenceService(
    private val resourceSettingRepository: ResourceSettingRepository,
    private val preferenceAccessPolicy: PreferenceAccessPolicy,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @Transactional(readOnly = true)
    fun getSettings(type: ResourceType, resourceId: Long, userId: Long): SettingsResponse {
        preferenceAccessPolicy.requireReadable(type, resourceId, userId)
        val settings = resourceSettingRepository.findByResourceTypeAndResourceId(type, resourceId)?.settings.orEmpty()
        val visible = if (type == ResourceType.ACCOUNT && resourceId != userId) {
            settings.filterKeys { it in SettingSchema.PUBLIC_ACCOUNT_KEYS }
        } else {
            settings
        }
        return SettingsResponse(type, resourceId, visible)
    }

    @Transactional
    fun updateSettings(type: ResourceType, resourceId: Long, userId: Long, raw: Map<String, Any?>): SettingsResponse {
        preferenceAccessPolicy.requireWritable(type, resourceId, userId)
        val changes = SettingSchema.filter(type, raw)
        if (changes.isEmpty()) {
            throw BusinessException(HttpStatus.BAD_REQUEST, "변경할 수 있는 설정 항목이 없습니다.")
        }
        SettingSchema.validate(type, changes)?.let { throw BusinessException(HttpStatus.BAD_REQUEST, it) }

        val setting = resourceSettingRepository.findByResourceTypeAndResourceId(type, resourceId)
            ?: resourceSettingRepository.save(ResourceSetting(resourceType = type, resourceId = resourceId))
        val previousStatus = setting.settings[SettingSchema.STATUS] as? String
        setting.merge(changes)

        if (type == ResourceType.ACCOUNT && SettingSchema.STATUS in changes) {
            val newStatus = setting.settings[SettingSchema.STATUS] as? String
            if (newStatus != previousStatus) {
                eventPublisher.publishEvent(AccountStatusChangedEvent(resourceId, previousStatus, newStatus, "USER"))
            }
        }
        return SettingsResponse(type, resourceId, setting.settings)
    }
}
