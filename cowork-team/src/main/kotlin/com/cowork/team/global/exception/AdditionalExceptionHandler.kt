package com.cowork.team.global.exception

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import team.themoment.sdk.exception.ExpectedException
import team.themoment.sdk.logging.logger.logger
import team.themoment.sdk.response.CommonApiResponse

@RestControllerAdvice
class AdditionalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(e: MethodArgumentNotValidException): ResponseEntity<CommonApiResponse<Nothing>> {
        val message = e.bindingResult.fieldErrors.firstOrNull()?.defaultMessage ?: "요청 값이 올바르지 않습니다."
        return ResponseEntity.status(
            HttpStatus.BAD_REQUEST,
        ).body(CommonApiResponse.error(message, HttpStatus.BAD_REQUEST))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(e: HttpMessageNotReadableException): ResponseEntity<CommonApiResponse<Nothing>> {
        logger().warn("Failed to read request body", e)
        return ResponseEntity.status(
            HttpStatus.BAD_REQUEST,
        ).body(CommonApiResponse.error("요청 본문을 읽을 수 없습니다.", HttpStatus.BAD_REQUEST))
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatch(
        e: MethodArgumentTypeMismatchException,
    ): ResponseEntity<CommonApiResponse<Nothing>> = ResponseEntity.status(
        HttpStatus.BAD_REQUEST,
    ).body(CommonApiResponse.error("파라미터 '${e.name}'의 타입이 올바르지 않습니다.", HttpStatus.BAD_REQUEST))

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingServletRequestParameter(
        e: MissingServletRequestParameterException,
    ): ResponseEntity<CommonApiResponse<Nothing>> = ResponseEntity.status(
        HttpStatus.BAD_REQUEST,
    ).body(CommonApiResponse.error("필수 파라미터 '${e.parameterName}'이(가) 누락되었습니다.", HttpStatus.BAD_REQUEST))

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFound(e: NoResourceFoundException): ResponseEntity<CommonApiResponse<Nothing>> =
        ResponseEntity.status(
            HttpStatus.NOT_FOUND,
        ).body(CommonApiResponse.error("요청한 API를 찾을 수 없습니다.", HttpStatus.NOT_FOUND))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleHttpRequestMethodNotSupported(
        e: HttpRequestMethodNotSupportedException,
    ): ResponseEntity<CommonApiResponse<Nothing>> = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .allow(*e.supportedHttpMethods.orEmpty().toTypedArray())
        .body(CommonApiResponse.error("지원하지 않는 HTTP 메서드입니다.", HttpStatus.METHOD_NOT_ALLOWED))

    @ExceptionHandler(ExpectedException::class)
    fun handleExpected(e: ExpectedException): ResponseEntity<CommonApiResponse<Nothing>> = ResponseEntity.status(
        e.statusCode,
    ).body(CommonApiResponse.error(e.message ?: "요청 처리 중 오류가 발생했습니다.", e.statusCode))

    @ExceptionHandler(Exception::class)
    fun handleInternal(e: Exception): ResponseEntity<CommonApiResponse<Nothing>> {
        logger().error("Caught unhandled exception", e)
        return ResponseEntity.internalServerError().body(
            CommonApiResponse.error("서버 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
        )
    }
}
