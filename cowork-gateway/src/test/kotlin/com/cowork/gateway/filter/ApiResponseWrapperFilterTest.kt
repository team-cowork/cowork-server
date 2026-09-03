package com.cowork.gateway.filter

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.reactivestreams.Publisher
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.http.server.reactive.MockServerHttpResponse
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchangeDecorator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper

class ApiResponseWrapperFilterTest :
    DescribeSpec({
        fun filter(objectMapper: ObjectMapper) = ApiResponseWrapperFilter(
            objectMapper = objectMapper,
            properties = ApiResponseWrapperProperties(),
            metrics = ApiResponseWrapperMetrics(SimpleMeterRegistry()),
        )

        describe("ApiResponseWrapperFilter는") {
            it("작은 JSON 응답을 CommonApiResponse로 감싼다") {
                // Given
                val objectMapper = ObjectMapper()
                val response = """{"id":1,"name":"cowork"}"""
                val exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/channel/channels/1").build(),
                )
                val chain = GatewayFilterChain { currentExchange ->
                    currentExchange.response.headers.contentType = MediaType.APPLICATION_JSON
                    currentExchange.response.writeWith(
                        Mono.just(currentExchange.response.bufferFactory().wrap(response.toByteArray())),
                    )
                }

                // When
                filter(objectMapper).filter(exchange, chain).block()

                // Then
                exchange.response.bodyAsString.block() shouldBe
                    """{"status":"OK","code":200,"message":"OK","data":{"id":1,"name":"cowork"}}"""
            }

            it("이미 CommonApiResponse인 JSON은 원본 bytes를 유지한다") {
                // Given
                val objectMapper = ObjectMapper()
                val response = """{ "status" : "OK", "code" : 200, "message" : "OK", "data" : { "id" : 1 } }"""
                val exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/channel/channels/1").build(),
                )
                val chain = GatewayFilterChain { currentExchange ->
                    currentExchange.response.headers.contentType = MediaType.APPLICATION_JSON
                    currentExchange.response.writeWith(
                        Mono.just(currentExchange.response.bufferFactory().wrap(response.toByteArray())),
                    )
                }

                // When
                filter(objectMapper).filter(exchange, chain).block()

                // Then
                exchange.response.bodyAsString.block() shouldBe response
            }

            it("Content-Length가 제한보다 큰 JSON은 원본 body를 통과시킨다") {
                // Given
                val objectMapper = ObjectMapper()
                val response = """{"items":[1,2,3]}"""
                val exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/channel/channels").build(),
                )
                val chain = GatewayFilterChain { currentExchange ->
                    currentExchange.response.headers.contentType = MediaType.APPLICATION_JSON
                    currentExchange.response.headers[HttpHeaders.CONTENT_LENGTH] = (1024 * 1024 + 1).toString()
                    currentExchange.response.writeWith(
                        Mono.just(currentExchange.response.bufferFactory().wrap(response.toByteArray())),
                    )
                }

                // When
                filter(objectMapper).filter(exchange, chain).block()

                // Then
                exchange.response.bodyAsString.block() shouldBe response
            }

            it("streaming 응답의 writeAndFlushWith를 평탄화하지 않는다") {
                // Given
                val objectMapper = ObjectMapper()
                val recordingResponse = RecordingResponse()
                val exchange = object : ServerWebExchangeDecorator(
                    MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/notification/notifications/stream").build(),
                    ),
                ) {
                    override fun getResponse(): ServerHttpResponse = recordingResponse
                }
                val chain = GatewayFilterChain { currentExchange ->
                    currentExchange.response.headers.contentType = MediaType.TEXT_EVENT_STREAM
                    currentExchange.response.writeAndFlushWith(
                        Flux.just(
                            Flux.just(
                                currentExchange.response.bufferFactory().wrap("data: connected\\n\\n".toByteArray()),
                            ),
                        ),
                    )
                }

                // When
                filter(objectMapper).filter(exchange, chain).block()

                // Then
                recordingResponse.writeAndFlushWithCalls shouldBe 1
                recordingResponse.writeWithCalls shouldBe 0
            }

            it("GraphQL 표준 응답 envelope를 변경하지 않는다") {
                // Given
                val objectMapper = ObjectMapper()
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
                filter(objectMapper).filter(exchange, chain).block()

                // Then
                exchange.response.bodyAsString.block() shouldBe body
            }

            it("AsyncAPI 문서를 공통 API 응답으로 감싸지 않는다") {
                // Given
                val objectMapper = ObjectMapper()
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
                filter(objectMapper).filter(exchange, chain).block()

                // Then
                exchange.response.bodyAsString.block() shouldBe body
            }
        }
    })

private class RecordingResponse : MockServerHttpResponse() {
    var writeWithCalls = 0
    var writeAndFlushWithCalls = 0

    override fun writeWithInternal(body: Publisher<out DataBuffer>): Mono<Void> {
        writeWithCalls += 1
        return super.writeWithInternal(body)
    }

    override fun writeAndFlushWithInternal(body: Publisher<out Publisher<out DataBuffer>>): Mono<Void> {
        writeAndFlushWithCalls += 1
        return super.writeAndFlushWithInternal(body)
    }
}
