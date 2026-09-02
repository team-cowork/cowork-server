package com.cowork.gateway.filter

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import java.util.concurrent.TimeUnit

@Component
class ApiResponseWrapperMetrics(private val meterRegistry: MeterRegistry) {

    fun record(
        exchange: ServerWebExchange,
        outcome: ApiResponseWrappingOutcome,
        sourceBytes: Long? = null,
        resultBytes: Long? = null,
        durationNanos: Long? = null,
    ) {
        val tags = arrayOf(
            "outcome",
            outcome.metricValue,
            "route",
            exchange.getAttribute<Route>(GATEWAY_ROUTE_ATTR)?.id.orEmpty().ifBlank { "unmatched" },
            "status",
            "${exchange.response.statusCode?.value()?.div(100) ?: 0}xx",
        )

        Counter.builder(REQUESTS_METRIC)
            .tags(*tags)
            .register(meterRegistry)
            .increment()

        sourceBytes?.let { bytes -> recordSize("source", bytes, tags) }
        resultBytes?.let { bytes -> recordSize("result", bytes, tags) }
        durationNanos?.let { nanos ->
            Timer.builder(DURATION_METRIC)
                .tags(*tags)
                .register(meterRegistry)
                .record(nanos, TimeUnit.NANOSECONDS)
        }
    }

    private fun recordSize(direction: String, bytes: Long, tags: Array<String>) {
        DistributionSummary.builder(SIZE_METRIC)
            .tag("direction", direction)
            .tags(*tags)
            .register(meterRegistry)
            .record(bytes.toDouble())
    }

    companion object {
        private const val REQUESTS_METRIC = "gateway.response.wrapper.requests"
        private const val DURATION_METRIC = "gateway.response.wrapper.duration"
        private const val SIZE_METRIC = "gateway.response.wrapper.bytes"
    }
}

enum class ApiResponseWrappingOutcome(val metricValue: String) {
    WRAPPED("wrapped"),
    ALREADY_WRAPPED("already_wrapped"),
    BYPASS_PATH("bypass_path"),
    BYPASS_CONTENT_TYPE("bypass_content_type"),
    BYPASS_STREAMING("bypass_streaming"),
    BYPASS_KNOWN_LARGE("bypass_known_large"),
    BYPASS_THRESHOLD("bypass_threshold"),
    BYPASS_EMPTY("bypass_empty"),
    ERROR("error"),
}
