package com.cowork.user.controller

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import team.themoment.sdk.exception.ExpectedException

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    data class ErrorResponse(
        val message: String,
    )

    @ExceptionHandler(ExpectedException::class)
    fun handleExpectedException(e: ExpectedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(e.statusCode).body(ErrorResponse(e.message ?: e.statusCode.reasonPhrase))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message = e.bindingResult.fieldErrors.firstOrNull()?.defaultMessage ?: "요청 값이 올바르지 않습니다."
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(message))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        log.warn("읽을 수 없는 요청 본문", e)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse("요청 본문을 읽을 수 없습니다."))
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse("파라미터 '${e.name}'의 타입이 올바르지 않습니다."))

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingServletRequestParameter(e: MissingServletRequestParameterException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse("필수 파라미터 '${e.parameterName}'이(가) 누락되었습니다."))

    @ExceptionHandler(Exception::class)
    fun handleInternal(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("처리되지 않은 예외 발생", e)
        return ResponseEntity.internalServerError().body(ErrorResponse("서버 오류가 발생했습니다."))
    }
}
