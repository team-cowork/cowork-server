package com.cowork.gateway.fallback.controller

import com.cowork.gateway.metrics.CircuitBreakerFallbackMetrics
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import team.themoment.sdk.response.CommonApiResponse

@RestController
class FallbackController(private val circuitBreakerFallbackMetrics: CircuitBreakerFallbackMetrics) {

    @RequestMapping("/fallback")
    fun fallback(exchange: ServerWebExchange): Mono<ResponseEntity<CommonApiResponse<Nothing>>> {
        val route = exchange.getAttribute<Route>(GATEWAY_ROUTE_ATTR)
        val cause = exchange.getAttribute<Throwable>(CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR)
        if (route != null && cause != null) circuitBreakerFallbackMetrics.record(route.id, cause)
        val body = CommonApiResponse.error(
            status = HttpStatus.SERVICE_UNAVAILABLE,
            message = "서비스가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도하십시오.",
        )
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body))
    }
}
