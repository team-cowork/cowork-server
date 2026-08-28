package com.cowork.gateway.filter

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper

class ApiResponseWrapperFilterTest :
    DescribeSpec({
        describe("ApiResponseWrapperFilter는") {
            it("GraphQL 표준 응답 envelope를 변경하지 않는다") {
                // Given
                val objectMapper = ObjectMapper()
                val filter = ApiResponseWrapperFilter(objectMapper)
                val body = """{"data":{"unifiedSearch":{"messages":[]}}}"""
                val exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/chat/graphql").build(),
                )
                val chain = GatewayFilterChain { currentExchange ->
                    currentExchange.response.headers.contentType = MediaType.APPLICATION_JSON
                    currentExchange.response.writeWith(
                        Mono.just(currentExchange.response.bufferFactory().wrap(body.toByteArray())),
                    )
                }

                // When
                filter.filter(exchange, chain).block()

                // Then
                exchange.response.bodyAsString.block() shouldBe body
            }

            it("AsyncAPI 문서를 공통 API 응답으로 감싸지 않는다") {
                // Given
                val objectMapper = ObjectMapper()
                val filter = ApiResponseWrapperFilter(objectMapper)
                val body = """{"asyncapi":"3.0.0","info":{"title":"Cowork Chat Events"}}"""
                val exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/chat/asyncapi.json").build(),
                )
                val chain = GatewayFilterChain { currentExchange ->
                    currentExchange.response.headers.contentType = MediaType.APPLICATION_JSON
                    currentExchange.response.writeWith(
                        Mono.just(currentExchange.response.bufferFactory().wrap(body.toByteArray())),
                    )
                }

                // When
                filter.filter(exchange, chain).block()

                // Then
                exchange.response.bodyAsString.block() shouldBe body
            }
        }
    })
