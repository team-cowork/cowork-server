package com.cowork.global

import com.cowork.global.error.BusinessException
import com.cowork.global.error.GlobalExceptionHandler
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.validation.BindingResult
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException

class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    @Test
    fun `비즈니스 예외는 지정한 상태 코드와 메시지로 응답한다`() {
        val response = handler.handleBusiness(BusinessException(HttpStatus.CONFLICT, "이미 존재합니다."))

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals(409, response.body?.status)
        assertEquals("이미 존재합니다.", response.body?.message)
    }

    @Test
    fun `검증 실패는 첫 번째 필드 오류를 메시지로 쓴다`() {
        val bindingResult = mockk<BindingResult>()
        every { bindingResult.fieldErrors } returns listOf(FieldError("request", "name", "비어 있을 수 없습니다"))
        val exception = mockk<MethodArgumentNotValidException>()
        every { exception.bindingResult } returns bindingResult

        val response = handler.handleValidation(exception)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("name: 비어 있을 수 없습니다", response.body?.message)
    }

    @Test
    fun `필드 오류가 없으면 기본 메시지를 쓴다`() {
        val bindingResult = mockk<BindingResult>()
        every { bindingResult.fieldErrors } returns emptyList()
        val exception = mockk<MethodArgumentNotValidException>()
        every { exception.bindingResult } returns bindingResult

        assertEquals("요청 값이 올바르지 않습니다.", handler.handleValidation(exception).body?.message)
    }
}
