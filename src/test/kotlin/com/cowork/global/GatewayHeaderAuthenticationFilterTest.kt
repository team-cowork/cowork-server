package com.cowork.global

import com.cowork.global.security.AuthUser
import com.cowork.global.security.GatewayHeaderAuthenticationFilter
import com.cowork.user.domain.UserRole
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

class GatewayHeaderAuthenticationFilterTest {

    private val filter = GatewayHeaderAuthenticationFilter()

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun principal() = SecurityContextHolder.getContext().authentication?.principal as AuthUser?

    private fun request(userId: String?, role: String?) = MockHttpServletRequest("GET", "/teams").apply {
        userId?.let { addHeader(GatewayHeaderAuthenticationFilter.USER_ID_HEADER, it) }
        role?.let { addHeader(GatewayHeaderAuthenticationFilter.USER_ROLE_HEADER, it) }
    }

    @Test
    fun `게이트웨이 헤더로 사용자와 권한을 설정한다`() {
        filter.doFilter(request("5", "ADMIN"), MockHttpServletResponse(), MockFilterChain())

        assertEquals(AuthUser(5, UserRole.ADMIN), principal())
    }

    @Test
    fun `알 수 없는 권한 헤더는 일반 멤버로 본다`() {
        filter.doFilter(request("5", "ROOT"), MockHttpServletResponse(), MockFilterChain())

        assertEquals(UserRole.MEMBER, principal()?.role)
    }

    @Test
    fun `숫자가 아닌 사용자 ID 헤더는 무시한다`() {
        filter.doFilter(request("abc", null), MockHttpServletResponse(), MockFilterChain())

        assertNull(principal())
    }

    @Test
    fun `이미 인증된 요청은 헤더로 덮어쓰지 않는다`() {
        val existing = AuthUser(1, UserRole.MEMBER)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(existing, null, emptyList())

        filter.doFilter(request("9", "ADMIN"), MockHttpServletResponse(), MockFilterChain())

        assertEquals(existing, principal())
    }
}
