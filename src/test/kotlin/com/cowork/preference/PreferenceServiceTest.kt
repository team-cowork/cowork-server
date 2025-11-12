package com.cowork.preference

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.global.error.BusinessException
import com.cowork.preference.domain.ChannelNotificationSetting
import com.cowork.preference.domain.ResourceSetting
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.event.AccountStatusChangedEvent
import com.cowork.preference.repository.ChannelNotificationSettingRepository
import com.cowork.preference.repository.ResourceSettingRepository
import com.cowork.preference.service.PreferenceAccessPolicy
import com.cowork.preference.service.PreferenceService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus

class PreferenceServiceTest {

    private val settingRepository = mockk<ResourceSettingRepository>(relaxed = true)
    private val notificationRepository = mockk<ChannelNotificationSettingRepository>(relaxed = true)
    private val accessPolicy = mockk<PreferenceAccessPolicy>()
    private val channelAccessChecker = mockk<ChannelAccessChecker>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = PreferenceService(
        settingRepository, notificationRepository, accessPolicy, channelAccessChecker, eventPublisher,
    )

    @BeforeEach
    fun allowAll() {
        every { accessPolicy.requireReadable(any(), any(), any()) } just runs
        every { accessPolicy.requireWritable(any(), any(), any()) } just runs
        every { channelAccessChecker.requireReadable(10, 1) } returns Channel(
            teamId = 1, projectId = null, name = "general", viewType = ChannelViewType.TEXT, description = null,
            isPrivate = false, position = 0, createdBy = 1, id = 10,
        )
    }

    private fun accountSetting(vararg pairs: Pair<String, Any?>) =
        ResourceSetting(resourceType = ResourceType.ACCOUNT, resourceId = 1, settings = mutableMapOf(*pairs))

    @Test
    fun `다른 사용자의 계정 설정은 상태만 보여준다`() {
        every { settingRepository.findByResourceTypeAndResourceId(ResourceType.ACCOUNT, 1) } returns
            accountSetting("status" to "ONLINE", "theme" to "BLACK")

        val response = service.getSettings(ResourceType.ACCOUNT, 1, 2)

        assertEquals(mapOf("status" to "ONLINE"), response.settings)
    }

    @Test
    fun `본인 계정 설정은 모든 항목을 보여준다`() {
        every { settingRepository.findByResourceTypeAndResourceId(ResourceType.ACCOUNT, 1) } returns
            accountSetting("status" to "ONLINE", "theme" to "BLACK")

        assertEquals(2, service.getSettings(ResourceType.ACCOUNT, 1, 1).settings.size)
    }

    @Test
    fun `변경할 수 있는 항목이 없으면 400을 반환한다`() {
        val exception = assertThrows<BusinessException> {
            service.updateSettings(ResourceType.ACCOUNT, 1, 1, mapOf("unknown" to true))
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }

    @Test
    fun `허용하지 않는 값이면 검증 메시지로 400을 반환한다`() {
        assertThrows<BusinessException> { service.updateSettings(ResourceType.ACCOUNT, 1, 1, mapOf("theme" to "PINK")) }
    }

    @Test
    fun `설정이 없으면 새로 만들고 변경 값을 합친다`() {
        every { settingRepository.findByResourceTypeAndResourceId(ResourceType.ACCOUNT, 1) } returns null
        every { settingRepository.save(any()) } answers { firstArg() }

        val response = service.updateSettings(ResourceType.ACCOUNT, 1, 1, mapOf("theme" to "BLACK"))

        assertEquals("BLACK", response.settings["theme"])
    }

    @Test
    fun `상태가 바뀌면 상태 변경 이벤트를 발행한다`() {
        every { settingRepository.findByResourceTypeAndResourceId(ResourceType.ACCOUNT, 1) } returns
            accountSetting("status" to "ONLINE")

        service.updateSettings(ResourceType.ACCOUNT, 1, 1, mapOf("status" to "DO_NOT_DISTURB"))

        verify { eventPublisher.publishEvent(AccountStatusChangedEvent(1, "ONLINE", "DO_NOT_DISTURB", "USER")) }
    }

    @Test
    fun `같은 상태로 다시 저장하면 이벤트를 발행하지 않는다`() {
        every { settingRepository.findByResourceTypeAndResourceId(ResourceType.ACCOUNT, 1) } returns
            accountSetting("status" to "ONLINE")

        service.updateSettings(ResourceType.ACCOUNT, 1, 1, mapOf("status" to "ONLINE"))

        verify(exactly = 0) { eventPublisher.publishEvent(any<AccountStatusChangedEvent>()) }
    }

    @Test
    fun `채널 알림 설정이 없으면 켜진 상태로 본다`() {
        every { notificationRepository.findByUserIdAndChannelId(1, 10) } returns null

        assertTrue(service.getChannelNotification(1, 10).notification)
    }

    @Test
    fun `채널 알림을 끄면 설정을 저장한다`() {
        every { notificationRepository.findByUserIdAndChannelId(1, 10) } returns null
        every { notificationRepository.save(any()) } answers { firstArg<ChannelNotificationSetting>() }

        assertFalse(service.updateChannelNotification(1, 10, false).notification)
    }
}
