package com.cowork.gateway.response.filter

import com.cowork.gateway.response.config.ApiResponseWrapperProperties
import com.cowork.gateway.response.metrics.ApiResponseWrapperMetrics
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import tools.jackson.databind.json.JsonMapper

class ApiResponseWrapperFilterTest :
    DescribeSpec({
        val objectMapper = JsonMapper.builder().build()
        val filter = ApiResponseWrapperFilter(
            objectMapper,
            ApiResponseWrapperProperties(),
            ApiResponseWrapperMetrics(SimpleMeterRegistry()),
        )

        fun completeWith(status: HttpStatus): MockServerWebExchange {
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/user/users/me"))
            val chain = mockk<GatewayFilterChain>()
            every { chain.filter(any()) } answers {
                val forwarded = firstArg<ServerWebExchange>()
                forwarded.response.statusCode = status
                forwarded.response.setComplete()
            }
            filter.filter(exchange, chain).block()
            return exchange
        }

        describe("ApiResponseWrapperFilter 클래스의 filter 메서드는") {
            context("요청 제한기가 429를 빈 본문으로 끝내면") {
                it("공통 오류 응답 본문을 JSON으로 채운다") {
                    val exchange = completeWith(HttpStatus.TOO_MANY_REQUESTS)

                    val body = objectMapper.readTree(exchange.response.bodyAsString.block())
                    exchange.response.headers.contentType shouldBe MediaType.APPLICATION_JSON
                    body.get("status").asString() shouldBe "TOO_MANY_REQUESTS"
                    body.get("code").asInt() shouldBe 429
                    body.get("message").asString() shouldBe "요청이 너무 많습니다. 잠시 후 다시 시도하십시오."
                }
            }

            context("다른 상태 코드로 빈 응답을 끝내면") {
                it("본문을 추가하지 않는다") {
                    val exchange = completeWith(HttpStatus.FORBIDDEN)

                    exchange.response.bodyAsString.block().orEmpty() shouldBe ""
                }
            }
        }
    })
