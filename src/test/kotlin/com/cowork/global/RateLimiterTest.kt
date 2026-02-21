package com.cowork.global

import com.cowork.global.ratelimit.RateLimiter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class RateLimiterTest {

    private class MutableClock(var instant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = instant
    }

    private val clock = MutableClock(Instant.parse("2026-01-20T12:00:00Z"))
    private val limiter = RateLimiter(limit = 2, window = Duration.ofMinutes(1), clock = clock)

    @Test
    fun `시간 창 안에서 허용 횟수를 넘으면 거부한다`() {
        assertTrue(limiter.tryAcquire("1.1.1.1"))
        assertTrue(limiter.tryAcquire("1.1.1.1"))
        assertFalse(limiter.tryAcquire("1.1.1.1"))
        assertTrue(limiter.tryAcquire("2.2.2.2"))
    }

    @Test
    fun `시간 창이 지나면 다시 허용한다`() {
        repeat(3) { limiter.tryAcquire("1.1.1.1") }
        clock.instant = clock.instant.plusSeconds(60)

        assertTrue(limiter.tryAcquire("1.1.1.1"))
    }

    @Test
    fun `지난 시간 창 기록을 정리한다`() {
        limiter.tryAcquire("1.1.1.1")
        clock.instant = clock.instant.plusSeconds(61)
        limiter.tryAcquire("2.2.2.2")

        limiter.evictExpired()

        assertEquals(1, limiter.size())
    }
}
