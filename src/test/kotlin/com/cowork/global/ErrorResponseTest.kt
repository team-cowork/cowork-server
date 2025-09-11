package com.cowork.global

import com.cowork.global.error.ErrorResponse
import com.cowork.global.logging.RequestIdFilter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class ErrorResponseTest {

    @AfterEach
    fun clear() = MDC.clear()

    @Test
    fun `오류 응답에 현재 요청 ID를 담는다`() {
        MDC.put(RequestIdFilter.MDC_KEY, "request-0001")

        assertEquals("request-0001", ErrorResponse(400, "요청 형식이 올바르지 않습니다.").requestId)
    }

    @Test
    fun `요청 ID가 없으면 비워 둔다`() {
        assertNull(ErrorResponse(500, "서버 내부 오류가 발생했습니다.").requestId)
    }
}
