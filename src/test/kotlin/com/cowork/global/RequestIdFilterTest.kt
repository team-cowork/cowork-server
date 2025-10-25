package com.cowork.global

import com.cowork.global.logging.RequestIdFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import jakarta.servlet.FilterChain

class RequestIdFilterTest {

    private val filter = RequestIdFilter()

    @Test
    fun `요청 헤더의 ID를 그대로 쓰고 응답 헤더에도 담는다`() {
        val request = MockHttpServletRequest("GET", "/teams")
        request.addHeader(RequestIdFilter.HEADER, "client-request-1234")
        val response = MockHttpServletResponse()
        var seen: String? = null

        filter.doFilter(request, response, FilterChain { _, _ -> seen = MDC.get(RequestIdFilter.MDC_KEY) })

        assertEquals("client-request-1234", seen)
        assertEquals("client-request-1234", response.getHeader(RequestIdFilter.HEADER))
    }

    @Test
    fun `형식이 잘못된 요청 ID는 새로 만든다`() {
        val request = MockHttpServletRequest("GET", "/teams")
        request.addHeader(RequestIdFilter.HEADER, "bad id with spaces")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertNotEquals("bad id with spaces", response.getHeader(RequestIdFilter.HEADER))
    }

    @Test
    fun `요청이 끝나면 MDC에서 요청 ID를 지운다`() {
        filter.doFilter(MockHttpServletRequest("GET", "/teams"), MockHttpServletResponse(), MockFilterChain())

        assertNull(MDC.get(RequestIdFilter.MDC_KEY))
    }
}
