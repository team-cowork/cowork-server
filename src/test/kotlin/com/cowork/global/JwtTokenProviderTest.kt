package com.cowork.global

import com.cowork.global.security.JwtProperties
import com.cowork.global.security.JwtTokenProvider
import com.cowork.user.domain.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JwtTokenProviderTest {

    private val secret = "test-secret-key-that-is-at-least-32-bytes-long"
    private val provider = JwtTokenProvider(JwtProperties(secret = secret))

    @Test
    fun `발급한 액세스 토큰에서 사용자 정보를 꺼낸다`() {
        val token = provider.createAccessToken(7, UserRole.ADMIN)

        val user = provider.parseAccessToken(token)

        assertEquals(7, user?.id)
        assertEquals(UserRole.ADMIN, user?.role)
    }

    @Test
    fun `형식이 잘못된 토큰은 null을 반환한다`() {
        assertNull(provider.parseAccessToken("not-a-jwt"))
    }
}
