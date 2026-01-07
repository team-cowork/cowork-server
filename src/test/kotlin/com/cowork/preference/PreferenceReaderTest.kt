package com.cowork.preference

import com.cowork.preference.domain.ResourceSetting
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.repository.ChannelNotificationSettingRepository
import com.cowork.preference.repository.ResourceSettingRepository
import com.cowork.preference.service.PreferenceReader
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PreferenceReaderTest {

    private val settingRepository = mockk<ResourceSettingRepository>()
    private val notificationRepository = mockk<ChannelNotificationSettingRepository>()
    private val reader = PreferenceReader(settingRepository, notificationRepository)

    @Test
    fun `채널 알림을 끈 사용자는 알림 대상에서 뺀다`() {
        every { notificationRepository.findMutedUserIds(10, listOf(1, 2, 3)) } returns listOf(2)

        assertEquals(setOf(1L, 3L), reader.filterNotifiable(10, listOf(1, 2, 3)))
    }

    @Test
    fun `대상이 없으면 저장소를 조회하지 않는다`() {
        assertEquals(emptySet<Long>(), reader.filterNotifiable(10, emptyList()))
        verify(exactly = 0) { notificationRepository.findMutedUserIds(any(), any()) }
    }

    @Test
    fun `방해 금지 상태인 사용자만 반환한다`() {
        every { settingRepository.findAllByResourceTypeAndResourceIdIn(ResourceType.ACCOUNT, listOf(1, 2)) } returns listOf(
            ResourceSetting(ResourceType.ACCOUNT, 1, mutableMapOf("status" to "DO_NOT_DISTURB")),
            ResourceSetting(ResourceType.ACCOUNT, 2, mutableMapOf("status" to "ONLINE")),
        )

        assertEquals(setOf(1L), reader.findDoNotDisturbUsers(listOf(1, 2)))
    }
}
