package com.cowork.global

import com.cowork.global.logging.RequestTimingInterceptor
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class RequestTimingInterceptorTest {

    private val interceptor = RequestTimingInterceptor(slowRequestMillis = 500)

    @Test
    fun `기준 시간 이상 걸린 요청을 느린 요청으로 본다`() {
        assertTrue(interceptor.isSlow(500))
        assertFalse(interceptor.isSlow(499))
    }

    @Test
    fun `시작 시각이 없는 요청은 무시한다`() {
        interceptor.afterCompletion(MockHttpServletRequest(), MockHttpServletResponse(), Any(), null)
    }

    @Test
    fun `요청 시작 시각을 기록한다`() {
        val request = MockHttpServletRequest()

        assertTrue(interceptor.preHandle(request, MockHttpServletResponse(), Any()))
        assertTrue(request.getAttribute("cowork.requestStartNanos") is Long)
    }
}
