package com.cowork.gateway.response.body

import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode

internal fun defaultApiErrorMessage(status: HttpStatusCode): String = when (HttpStatus.resolve(status.value())) {
    HttpStatus.BAD_REQUEST -> "유효하지 않은 요청입니다."
    HttpStatus.UNAUTHORIZED -> "인증이 필요합니다."
    HttpStatus.FORBIDDEN -> "접근 권한이 없습니다."
    HttpStatus.NOT_FOUND -> "요청한 대상을 찾을 수 없습니다."
    HttpStatus.METHOD_NOT_ALLOWED -> "지원하지 않는 요청 메서드입니다."
    HttpStatus.NOT_ACCEPTABLE -> "지원하지 않는 응답 형식입니다."
    HttpStatus.REQUEST_TIMEOUT -> "요청 시간이 초과되었습니다."
    HttpStatus.CONTENT_TOO_LARGE -> "요청 본문이 너무 큽니다."
    HttpStatus.UNSUPPORTED_MEDIA_TYPE -> "지원하지 않는 요청 형식입니다."
    HttpStatus.TOO_MANY_REQUESTS -> "요청이 너무 많습니다. 잠시 후 다시 시도하십시오."
    HttpStatus.BAD_GATEWAY -> "서비스에서 유효한 응답을 받지 못했습니다."
    HttpStatus.SERVICE_UNAVAILABLE -> "서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도하십시오."
    HttpStatus.GATEWAY_TIMEOUT -> "서비스 응답 시간이 초과되었습니다."
    else -> "요청을 처리할 수 없습니다."
}
