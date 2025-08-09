package com.cowork.global

import com.cowork.global.security.JwtProperties
import com.cowork.global.security.JwtTokenProvider
import com.cowork.user.domain.UserRole
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Date

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

    @Test
    fun `만료된 토큰은 null을 반환한다`() {
        val expired = JwtTokenProvider(JwtProperties(secret = secret, accessTokenTtl = Duration.ofSeconds(-1)))
            .createAccessToken(1, UserRole.MEMBER)

        assertNull(provider.parseAccessToken(expired))
    }

    @Test
    fun `다른 키로 서명한 토큰은 null을 반환한다`() {
        val other = JwtTokenProvider(JwtProperties(secret = "another-secret-key-that-is-at-least-32-bytes"))
            .createAccessToken(1, UserRole.MEMBER)

        assertNull(provider.parseAccessToken(other))
    }

    @Test
    fun `액세스 토큰 유형이 아니면 null을 반환한다`() {
        val token = Jwts.builder()
            .subject("1")
            .claim("role", "MEMBER")
            .claim("typ", "refresh")
            .expiration(Date(System.currentTimeMillis() + 60_000))
            .signWith(Keys.hmacShaKeyFor(secret.toByteArray()))
            .compact()

        assertNull(provider.parseAccessToken(token))
    }
}
