package com.cowork.notification

import com.cowork.notification.domain.DevicePlatform
import com.cowork.notification.domain.DeviceToken
import com.cowork.notification.repository.DeviceTokenRepository
import com.cowork.notification.service.DeviceTokenService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeviceTokenServiceTest {

    private val repository = mockk<DeviceTokenRepository>(relaxed = true)
    private val service = DeviceTokenService(repository)

    @Test
    fun `처음 보는 토큰은 새로 저장한다`() {
        every { repository.findByToken("fcm") } returns null
        every { repository.save(any()) } answers { firstArg() }

        service.register(1, "fcm", DevicePlatform.ANDROID)

        verify { repository.save(match<DeviceToken> { it.userId == 1L && it.platform == DevicePlatform.ANDROID }) }
    }

    @Test
    fun `다른 사용자가 쓰던 토큰은 새 사용자에게 옮긴다`() {
        val existing = DeviceToken(userId = 2, token = "fcm", platform = DevicePlatform.WEB)
        every { repository.findByToken("fcm") } returns existing

        service.register(1, "fcm", DevicePlatform.IOS)

        assertEquals(1, existing.userId)
        assertEquals(DevicePlatform.IOS, existing.platform)
        verify(exactly = 0) { repository.save(any()) }
    }
}
