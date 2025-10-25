package com.cowork.auth

import com.cowork.auth.client.DataGsmClient
import com.cowork.auth.domain.RefreshToken
import com.cowork.auth.repository.RefreshTokenRepository
import com.cowork.auth.service.AuthService
import com.cowork.global.security.JwtProperties
import com.cowork.global.security.JwtTokenProvider
import com.cowork.user.domain.User
import com.cowork.user.repository.UserRepository
import com.cowork.user.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Optional

class RefreshTokenLimitTest {

    private val clock = Clock.fixed(Instant.parse("2025-09-20T00:00:00Z"), ZoneId.of("Asia/Seoul"))
    private val jwtProperties = JwtProperties(secret = "test-secret-key-that-is-at-least-32-bytes-long")
    private val refreshTokenRepository = mockk<RefreshTokenRepository>(relaxed = true)
    private val userRepository = mockk<UserRepository>()
    private val service = AuthService(
        mockk<DataGsmClient>(), mockk<UserService>(), userRepository, refreshTokenRepository,
        JwtTokenProvider(jwtProperties), jwtProperties, clock,
    )

    private fun token(id: Long) = RefreshToken(userId = 1, tokenHash = "hash$id", expiresAt = LocalDateTime.now(clock).plusDays(1), id = id)

    @Test
    fun `기기 수 제한을 넘으면 오래된 리프레시 토큰부터 지운다`() {
        val current = token(100)
        val tokens = (1L..6L).map(::token)
        every { refreshTokenRepository.findByTokenHash(any()) } returns current
        every { userRepository.findById(1) } returns
            Optional.of(User(dataGsmId = 1, name = "홍길동", email = "a@gsm.hs.kr", sex = "MAN", id = 1))
        every { refreshTokenRepository.findAllByUserIdOrderByIdAsc(1) } returns tokens
        every { refreshTokenRepository.save(any<RefreshToken>()) } answers { firstArg() }

        service.refresh("raw")

        verify { refreshTokenRepository.deleteAll(tokens.take(2)) }
    }
}
