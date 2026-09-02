package com.cowork.gateway.filter

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange

class ApiResponseWrapperMetricsTest :
    DescribeSpec({
        it("wrapping 결과를 route와 상태군 tag로 기록한다") {
            // Given
            val registry = SimpleMeterRegistry()
            val metrics = ApiResponseWrapperMetrics(registry)
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/channel/channels").build())
            exchange.response.statusCode = HttpStatus.OK

            // When
            metrics.record(
                exchange = exchange,
                outcome = ApiResponseWrappingOutcome.WRAPPED,
                sourceBytes = 128,
                resultBytes = 192,
                durationNanos = 1_000,
            )

            // Then
            registry.find("gateway.response.wrapper.requests")
                .tags("outcome", "wrapped", "route", "unmatched", "status", "2xx")
                .counter()
                ?.count() shouldBe 1.0
            registry.find("gateway.response.wrapper.bytes")
                .tags("direction", "source", "outcome", "wrapped", "route", "unmatched", "status", "2xx")
                .summary()
                ?.totalAmount() shouldBe 128.0
        }
    })
