package com.cowork.auth

import com.cowork.auth.domain.RefreshToken
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class RefreshTokenTest {

    private val now = LocalDateTime.of(2025, 9, 1, 12, 0)

    @Test
    fun `만료 시각이 지나지 않았으면 유효하다`() {
        val token = RefreshToken(userId = 1, tokenHash = "hash", expiresAt = now.plusSeconds(1))

        assertFalse(token.isExpired(now))
    }

    @Test
    fun `만료 시각과 같으면 만료로 본다`() {
        val token = RefreshToken(userId = 1, tokenHash = "hash", expiresAt = now)

        assertTrue(token.isExpired(now))
    }
}
