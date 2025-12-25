package com.cowork.notification

import com.cowork.notification.domain.DevicePlatform
import com.cowork.notification.domain.DeviceToken
import com.cowork.notification.domain.NotificationMessage
import com.cowork.notification.domain.NotificationType
import com.cowork.notification.push.PushSender
import com.cowork.notification.repository.DeviceTokenRepository
import com.cowork.notification.service.NotificationService
import com.cowork.notification.service.SseEmitterRegistry
import com.cowork.preference.service.PreferenceReader
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class NotificationServiceTest {

    private val deviceTokenRepository = mockk<DeviceTokenRepository>(relaxed = true)
    private val sseEmitterRegistry = mockk<SseEmitterRegistry>(relaxed = true)
    private val pushSender = mockk<PushSender>()
    private val preferenceReader = mockk<PreferenceReader>()
    private val service = NotificationService(deviceTokenRepository, sseEmitterRegistry, pushSender, preferenceReader)

    private val message = NotificationMessage(NotificationType.CHAT_MESSAGE, "#general", "안녕하세요")

    @Test
    fun `방해 금지 상태인 사용자에게는 푸시를 보내지 않지만 실시간 알림은 보낸다`() {
        every { preferenceReader.findDoNotDisturbUsers(setOf(1L, 2L)) } returns setOf(2L)
        every { deviceTokenRepository.findAllByUserIdIn(setOf(1L)) } returns
            listOf(DeviceToken(userId = 1, token = "token-1", platform = DevicePlatform.ANDROID))
        every { pushSender.send(listOf("token-1"), message) } returns emptyList()

        service.notify(listOf(1L, 2L), message)

        verify { sseEmitterRegistry.send(1, NotificationService.EVENT_NAME, message) }
        verify { sseEmitterRegistry.send(2, NotificationService.EVENT_NAME, message) }
        verify { pushSender.send(listOf("token-1"), message) }
    }

    @Test
    fun `유효하지 않은 기기 토큰은 삭제한다`() {
        every { preferenceReader.findDoNotDisturbUsers(setOf(1L)) } returns emptySet()
        every { deviceTokenRepository.findAllByUserIdIn(setOf(1L)) } returns listOf(
            DeviceToken(userId = 1, token = "valid", platform = DevicePlatform.IOS),
            DeviceToken(userId = 1, token = "expired", platform = DevicePlatform.IOS),
        )
        every { pushSender.send(any(), message) } returns listOf("expired")

        service.notify(listOf(1L), message)

        verify { deviceTokenRepository.deleteAllByTokenIn(listOf("expired")) }
    }

    @Test
    fun `수신자가 없으면 아무것도 보내지 않는다`() {
        service.notify(emptyList(), message)

        verify(exactly = 0) { pushSender.send(any(), any()) }
    }
}
