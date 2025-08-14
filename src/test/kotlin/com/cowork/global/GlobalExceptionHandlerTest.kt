package com.cowork.global

import com.cowork.global.error.BusinessException
import com.cowork.global.error.GlobalExceptionHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    @Test
    fun `비즈니스 예외는 지정한 상태 코드와 메시지로 응답한다`() {
        val response = handler.handleBusiness(BusinessException(HttpStatus.CONFLICT, "이미 존재합니다."))

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals(409, response.body?.status)
        assertEquals("이미 존재합니다.", response.body?.message)
    }
}
