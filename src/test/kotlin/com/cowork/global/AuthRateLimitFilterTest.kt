package com.cowork.global

import com.cowork.global.ratelimit.AuthRateLimitFilter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock

class AuthRateLimitFilterTest {

    private val filter = AuthRateLimitFilter(jacksonObjectMapper().findAndRegisterModules(), Clock.systemUTC(), 1)

    private fun call(method: String, uri: String): MockHttpServletResponse {
        val response = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest(method, uri), response, MockFilterChain())
        return response
    }

    @Test
    fun `허용 횟수를 넘은 로그인 요청은 429로 응답한다`() {
        assertEquals(200, call("POST", "/auth/token").status)
        val blocked = call("POST", "/auth/token")

        assertEquals(429, blocked.status)
        assertEquals(true, blocked.contentAsString.contains("429"))
    }

    @Test
    fun `제한 대상이 아닌 경로는 횟수를 세지 않는다`() {
        repeat(3) { assertEquals(200, call("GET", "/teams").status) }
        repeat(3) { assertEquals(200, call("GET", "/auth/token").status) }
    }
}
