package com.cowork.team.controller

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import team.themoment.sdk.exception.ExpectedException

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    data class ErrorResponse(val message: String)

    @ExceptionHandler(ExpectedException::class)
    fun handleExpectedException(e: ExpectedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(e.statusCode).body(ErrorResponse(e.message ?: e.statusCode.reasonPhrase))

    @ExceptionHandler(Exception::class)
    fun handleInternal(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("처리되지 않은 예외 발생", e)
        return ResponseEntity.internalServerError().body(ErrorResponse("서버 오류가 발생했습니다."))
    }
}
