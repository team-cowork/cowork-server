package com.cowork.global

import com.cowork.global.security.AuthUser
import com.cowork.global.security.JwtAuthenticationFilter
import com.cowork.global.security.JwtTokenProvider
import com.cowork.user.domain.UserRole
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

class JwtAuthenticationFilterTest {

    private val provider = mockk<JwtTokenProvider>()
    private val filter = JwtAuthenticationFilter(provider)

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun principal() = SecurityContextHolder.getContext().authentication?.principal as AuthUser?

    @Test
    fun `Bearer 토큰이 유효하면 인증 정보를 저장한다`() {
        every { provider.parseAccessToken("token") } returns AuthUser(1, UserRole.MEMBER)
        val request = MockHttpServletRequest("GET", "/users/me")
        request.addHeader("Authorization", "Bearer token")

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        assertEquals(1, principal()?.id)
        assertEquals("ROLE_MEMBER", SecurityContextHolder.getContext().authentication?.authorities?.single()?.authority)
    }

    @Test
    fun `토큰이 유효하지 않으면 인증 정보 없이 다음 필터로 넘긴다`() {
        every { provider.parseAccessToken(any()) } returns null
        val request = MockHttpServletRequest("GET", "/users/me")
        request.addHeader("Authorization", "Bearer broken")
        val chain = MockFilterChain()

        filter.doFilter(request, MockHttpServletResponse(), chain)

        assertNull(principal())
        assertEquals(request, chain.request)
    }
}
