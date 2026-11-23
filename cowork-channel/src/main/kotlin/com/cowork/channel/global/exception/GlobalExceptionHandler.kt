package com.cowork.channel.global.exception

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MultipartException
import org.springframework.web.servlet.resource.NoResourceFoundException
import team.themoment.sdk.exception.ExpectedException
import team.themoment.sdk.logging.logger.logger

@RestControllerAdvice
class GlobalExceptionHandler {

    data class ErrorResponse(val message: String)

    @ExceptionHandler(ExpectedException::class)
    fun handleExpectedException(e: ExpectedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(e.statusCode).body(ErrorResponse(e.message ?: e.statusCode.reasonPhrase))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(
            ErrorResponse(e.bindingResult.fieldErrors.firstOrNull()?.defaultMessage ?: "요청 값이 올바르지 않습니다."),
        )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMessageNotReadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse("요청 본문이 올바르지 않습니다."))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse("파라미터 타입이 올바르지 않습니다. name=${e.name}"))

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingHeader(e: MissingRequestHeaderException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse("필수 헤더가 누락되었습니다. header=${e.headerName}"))

    @ExceptionHandler(MultipartException::class)
    fun handleMultipartException(e: MultipartException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse("파일 업로드에 실패했습니다."))

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFound(e: NoResourceFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse("요청한 API를 찾을 수 없습니다."))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(e: HttpRequestMethodNotSupportedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .allow(*e.supportedHttpMethods.orEmpty().toTypedArray())
            .body(ErrorResponse("지원하지 않는 HTTP 메서드입니다."))

    @ExceptionHandler(Exception::class)
    fun handleInternal(e: Exception): ResponseEntity<ErrorResponse> {
        logger().error("Caught unhandled exception", e)
        return ResponseEntity.internalServerError().body(ErrorResponse("서버 오류가 발생했습니다."))
    }
}
