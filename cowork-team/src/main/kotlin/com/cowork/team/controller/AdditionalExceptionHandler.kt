package com.cowork.team.controller

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import team.themoment.sdk.exception.ExpectedException
import team.themoment.sdk.response.CommonApiResponse

@RestControllerAdvice
class AdditionalExceptionHandler {
    private val log = LoggerFactory.getLogger(AdditionalExceptionHandler::class.java)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(e: MethodArgumentNotValidException): ResponseEntity<CommonApiResponse<Nothing>> {
        val message = e.bindingResult.fieldErrors.firstOrNull()?.defaultMessage ?: "요청 값이 올바르지 않습니다."
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CommonApiResponse.error(message, HttpStatus.BAD_REQUEST))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(e: HttpMessageNotReadableException): ResponseEntity<CommonApiResponse<Nothing>> {
        log.warn("읽을 수 없는 요청 본문", e)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CommonApiResponse.error("요청 본문을 읽을 수 없습니다.", HttpStatus.BAD_REQUEST))
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<CommonApiResponse<Nothing>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CommonApiResponse.error("파라미터 '${e.name}'의 타입이 올바르지 않습니다.", HttpStatus.BAD_REQUEST))

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingServletRequestParameter(e: MissingServletRequestParameterException): ResponseEntity<CommonApiResponse<Nothing>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(CommonApiResponse.error("필수 파라미터 '${e.parameterName}'이(가) 누락되었습니다.", HttpStatus.BAD_REQUEST))

    @ExceptionHandler(ExpectedException::class)
    fun handleExpected(e: ExpectedException): ResponseEntity<CommonApiResponse<Nothing>> =
        ResponseEntity.status(e.statusCode).body(CommonApiResponse.error(e.message ?: "요청 처리 중 오류가 발생했습니다.", e.statusCode))

    @ExceptionHandler(RestClientResponseException::class)
    fun handleRestClientResponse(e: RestClientResponseException): ResponseEntity<CommonApiResponse<Nothing>> {
        val status = HttpStatus.resolve(e.statusCode.value()) ?: HttpStatus.BAD_GATEWAY
        return ResponseEntity.status(status).body(CommonApiResponse.error("설정 서비스 요청에 실패했습니다.", status))
    }

    @ExceptionHandler(Exception::class)
    fun handleInternal(e: Exception): ResponseEntity<CommonApiResponse<Nothing>> {
        log.error("처리되지 않은 예외 발생", e)
        return ResponseEntity.internalServerError().body(CommonApiResponse.error("서버 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR))
    }
}
