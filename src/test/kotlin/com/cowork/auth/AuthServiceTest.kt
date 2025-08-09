package com.cowork.auth

import com.cowork.auth.client.DataGsmClient
import com.cowork.auth.client.DataGsmUserInfo
import com.cowork.auth.domain.RefreshToken
import com.cowork.auth.repository.RefreshTokenRepository
import com.cowork.auth.service.AuthService
import com.cowork.auth.service.TokenHasher
import com.cowork.global.error.BusinessException
import com.cowork.global.security.JwtProperties
import com.cowork.global.security.JwtTokenProvider
import com.cowork.user.domain.User
import com.cowork.user.domain.UserRole
import com.cowork.user.repository.UserRepository
import com.cowork.user.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Optional

class AuthServiceTest {

    private val clock = Clock.fixed(Instant.parse("2025-09-01T00:00:00Z"), ZoneId.of("Asia/Seoul"))
    private val now = LocalDateTime.now(clock)
    private val jwtProperties = JwtProperties(secret = "test-secret-key-that-is-at-least-32-bytes-long")
    private val dataGsmClient = mockk<DataGsmClient>()
    private val userService = mockk<UserService>()
    private val userRepository = mockk<UserRepository>()
    private val refreshTokenRepository = mockk<RefreshTokenRepository>(relaxed = true)
    private val authService = AuthService(
        dataGsmClient,
        userService,
        userRepository,
        refreshTokenRepository,
        JwtTokenProvider(jwtProperties),
        jwtProperties,
        clock,
    )

    private val user = User(dataGsmId = 10, name = "홍길동", email = "s25001@gsm.hs.kr", sex = "MAN", role = UserRole.MEMBER, id = 1)

    @Test
    fun `리프레시 토큰을 사용하면 기존 토큰을 삭제하고 새 토큰을 발급한다`() {
        val raw = "raw-refresh-token"
        val stored = RefreshToken(userId = 1, tokenHash = TokenHasher.hash(raw), expiresAt = now.plusDays(1), id = 5)
        every { refreshTokenRepository.findByTokenHash(TokenHasher.hash(raw)) } returns stored
        every { userRepository.findById(1) } returns Optional.of(user)
        val saved = slot<RefreshToken>()
        every { refreshTokenRepository.save(capture(saved)) } answers { saved.captured }

        val response = authService.refresh(raw)

        verify { refreshTokenRepository.delete(stored) }
        assertNotEquals(raw, response.refreshToken)
        assertEquals(TokenHasher.hash(response.refreshToken), saved.captured.tokenHash)
        assertEquals(now.plus(jwtProperties.refreshTokenTtl), saved.captured.expiresAt)
    }

    @Test
    fun `만료된 리프레시 토큰은 거부한다`() {
        val raw = "expired"
        every { refreshTokenRepository.findByTokenHash(any()) } returns
            RefreshToken(userId = 1, tokenHash = TokenHasher.hash(raw), expiresAt = now.minusSeconds(1))

        val exception = assertThrows<BusinessException> { authService.refresh(raw) }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    @Test
    fun `존재하지 않는 리프레시 토큰은 거부한다`() {
        every { refreshTokenRepository.findByTokenHash(any()) } returns null

        val exception = assertThrows<BusinessException> { authService.refresh("unknown") }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    @Test
    fun `다른 사용자의 리프레시 토큰으로는 로그아웃할 수 없다`() {
        every { refreshTokenRepository.findByTokenHash(any()) } returns
            RefreshToken(userId = 2, tokenHash = "hash", expiresAt = now.plusDays(1))

        val exception = assertThrows<BusinessException> { authService.signOut(1, "token") }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
        verify(exactly = 0) { refreshTokenRepository.delete(any()) }
    }

    @Test
    fun `학생이 아닌 DataGSM 계정은 로그인할 수 없다`() {
        every { dataGsmClient.exchangeCode(any(), any(), any()) } returns "access"
        every { dataGsmClient.fetchUserInfo("access") } returns DataGsmUserInfo(id = 1, email = "teacher@gsm.hs.kr", isStudent = false)

        val exception = assertThrows<BusinessException> { authService.login("code", "verifier", "uri", null) }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }
}
