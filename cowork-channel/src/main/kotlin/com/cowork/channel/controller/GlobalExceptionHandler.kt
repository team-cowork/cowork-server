package com.cowork.channel.controller

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import team.themoment.sdk.exception.ExpectedException

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    data class ErrorResponse(val message: String)

    @ExceptionHandler(ExpectedException::class)
    fun handleExpectedException(e: ExpectedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(e.statusCode).body(ErrorResponse(e.message ?: e.statusCode.reasonPhrase))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMessageNotReadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse("요청 본문이 올바르지 않습니다."))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse("파라미터 타입이 올바르지 않습니다. name=${e.name}"))

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingHeader(e: MissingRequestHeaderException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse("필수 헤더가 누락되었습니다. header=${e.headerName}"))

    @ExceptionHandler(Exception::class)
    fun handleInternal(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("처리되지 않은 예외 발생", e)
        return ResponseEntity.internalServerError().body(ErrorResponse("서버 오류가 발생했습니다."))
    }
}
