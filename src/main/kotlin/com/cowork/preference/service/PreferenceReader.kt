package com.cowork.preference.service

import com.cowork.preference.domain.ResourceType
import com.cowork.preference.domain.SettingSchema
import com.cowork.preference.repository.ChannelNotificationSettingRepository
import com.cowork.preference.repository.ResourceSettingRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 다른 도메인이 설정 값을 조회할 때 사용하는 읽기 전용 진입점.
 */
@Component
class PreferenceReader(
    private val resourceSettingRepository: ResourceSettingRepository,
    private val channelNotificationSettingRepository: ChannelNotificationSettingRepository,
) {

    @Transactional(readOnly = true)
    fun filterNotifiable(channelId: Long, userIds: Collection<Long>): Set<Long> {
        if (userIds.isEmpty()) return emptySet()
        val muted = channelNotificationSettingRepository.findMutedUserIds(channelId, userIds).toSet()
        return userIds.toSet() - muted
    }

    @Transactional(readOnly = true)
    fun findDoNotDisturbUsers(userIds: Collection<Long>): Set<Long> {
        if (userIds.isEmpty()) return emptySet()
        return resourceSettingRepository.findAllByResourceTypeAndResourceIdIn(ResourceType.ACCOUNT, userIds)
            .filter { it.settings[SettingSchema.STATUS] == SettingSchema.STATUS_DO_NOT_DISTURB }
            .map { it.resourceId }
            .toSet()
    }

    @Transactional(readOnly = true)
    fun findVoiceMaxParticipants(channelId: Long): Int? =
        (resourceSettingRepository.findByResourceTypeAndResourceId(ResourceType.VOICE_CHANNEL, channelId)
            ?.settings?.get(SettingSchema.MAX_PARTICIPANTS) as? Number)?.toInt()
}
