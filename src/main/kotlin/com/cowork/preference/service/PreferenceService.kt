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

    @Transactional
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
}
