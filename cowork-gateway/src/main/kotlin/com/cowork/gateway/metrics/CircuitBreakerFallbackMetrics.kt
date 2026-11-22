package com.cowork.gateway.metrics

import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.micrometer.core.instrument.MeterRegistry
import io.netty.channel.ConnectTimeoutException
import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException
import org.springframework.cloud.gateway.config.GatewayProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.TimeoutException
import io.netty.handler.timeout.TimeoutException as NettyTimeoutException

@Component
class CircuitBreakerFallbackMetrics(private val meterRegistry: MeterRegistry, gatewayProperties: GatewayProperties) {
    init {
        gatewayProperties.routes
            .filter { route -> route.isEnabled && route.filters.any { it.name == "CircuitBreaker" } }
            .mapNotNull { it.id }
            .forEach(::registerRoute)
    }

    fun registerRoute(route: String) {
        // 첫 거절도 Prometheus의 increase()에 잡히도록 0부터 노출한다.
        Reason.entries.forEach { counter(route, it) }
    }

    fun record(route: String, error: Throwable) {
        val cause = if (error is NoFallbackAvailableException) error.cause ?: error else error
        val reason = when {
            cause is BulkheadFullException -> Reason.BULKHEAD_FULL
            cause is CallNotPermittedException -> Reason.CIRCUIT_REJECTED
            cause is TimeoutException -> Reason.TIMEOUT
            cause is NettyTimeoutException || cause is ConnectTimeoutException -> Reason.TIMEOUT
            cause is ResponseStatusException && cause.statusCode == HttpStatus.GATEWAY_TIMEOUT -> Reason.TIMEOUT
            else -> Reason.OTHER
        }
        counter(route, reason).increment()
    }

    private fun counter(route: String, reason: Reason) =
        meterRegistry.counter("gateway.circuitbreaker.fallbacks", "route", route, "reason", reason.name.lowercase())

    private enum class Reason { BULKHEAD_FULL, CIRCUIT_REJECTED, TIMEOUT, OTHER }
}
