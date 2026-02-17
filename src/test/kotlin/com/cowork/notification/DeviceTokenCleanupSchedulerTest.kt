package com.cowork.notification

import com.cowork.notification.repository.DeviceTokenRepository
import com.cowork.notification.service.DeviceTokenCleanupScheduler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class DeviceTokenCleanupSchedulerTest {

    private val repository = mockk<DeviceTokenRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-02-20T19:30:00Z"), ZoneId.of("Asia/Seoul"))
    private val scheduler = DeviceTokenCleanupScheduler(repository, clock)

    @Test
    fun `60일 동안 다시 등록되지 않은 토큰을 지운다`() {
        every { repository.deleteAllRegisteredBefore(any()) } returns 4

        scheduler.deleteStaleTokens()

        verify { repository.deleteAllRegisteredBefore(LocalDateTime.of(2025, 12, 23, 4, 30)) }
    }
}
