package com.cowork.user.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import team.themoment.sdk.exception.ExpectedException

@RestControllerAdvice
class GlobalExceptionHandler {

    data class ErrorResponse(val message: String)

    @ExceptionHandler(ExpectedException::class)
    fun handleExpectedException(e: ExpectedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(e.statusCode).body(ErrorResponse(e.message ?: e.statusCode.reasonPhrase))

    @ExceptionHandler(Exception::class)
    fun handleInternal(e: Exception): ResponseEntity<ErrorResponse> =
        ResponseEntity.internalServerError().body(ErrorResponse("서버 오류가 발생했습니다."))
}
