package com.cowork.auth

import com.cowork.auth.repository.RefreshTokenRepository
import com.cowork.auth.service.RefreshTokenCleanupScheduler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class RefreshTokenCleanupSchedulerTest {

    private val clock = Clock.fixed(Instant.parse("2025-09-01T19:00:00Z"), ZoneId.of("Asia/Seoul"))
    private val repository = mockk<RefreshTokenRepository>()
    private val scheduler = RefreshTokenCleanupScheduler(repository, clock)

    @Test
    fun `현재 시각 기준으로 만료된 토큰을 삭제한다`() {
        every { repository.deleteExpired(any()) } returns 3

        scheduler.deleteExpiredTokens()

        verify { repository.deleteExpired(LocalDateTime.of(2025, 9, 2, 4, 0)) }
    }
}
