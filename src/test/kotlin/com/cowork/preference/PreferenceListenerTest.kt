package com.cowork.preference

import com.cowork.channel.event.ChannelDeletedEvent
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.repository.ChannelNotificationSettingRepository
import com.cowork.preference.repository.ResourceSettingRepository
import com.cowork.preference.service.PreferenceLifecycleListener
import com.cowork.team.event.TeamDeletedEvent
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class PreferenceListenerTest {

    private val settingRepository = mockk<ResourceSettingRepository>(relaxed = true)
    private val notificationRepository = mockk<ChannelNotificationSettingRepository>(relaxed = true)
    private val lifecycleListener = PreferenceLifecycleListener(settingRepository, notificationRepository)

    @Test
    fun `채널을 삭제하면 채널 설정과 알림 설정을 모두 지운다`() {
        lifecycleListener.onChannelDeleted(ChannelDeletedEvent(10, 1))

        verify { settingRepository.deleteByResource(ResourceType.TEXT_CHANNEL, 10) }
        verify { settingRepository.deleteByResource(ResourceType.VOICE_CHANNEL, 10) }
        verify { notificationRepository.deleteAllByChannelId(10) }
    }

    @Test
    fun `팀을 삭제하면 팀 설정을 지운다`() {
        lifecycleListener.onTeamDeleted(TeamDeletedEvent(1))

        verify { settingRepository.deleteByResource(ResourceType.TEAM, 1) }
    }
}
