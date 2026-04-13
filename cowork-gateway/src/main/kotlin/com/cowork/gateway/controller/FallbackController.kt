package com.cowork.gateway.controller

import com.cowork.gateway.response.CommonApiResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
class FallbackController {

    @RequestMapping("/fallback")
    fun fallback(): Mono<ResponseEntity<CommonApiResponse<Nothing>>> {
        val body = CommonApiResponse.error(
            httpStatus = HttpStatus.SERVICE_UNAVAILABLE,
            message = "서비스가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도해주세요.",
        )
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body))
    }
}
