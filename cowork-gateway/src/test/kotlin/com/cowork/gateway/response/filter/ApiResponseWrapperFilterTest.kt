package com.cowork.gateway.response.filter

import com.cowork.gateway.response.config.ApiResponseWrapperProperties
import com.cowork.gateway.response.metrics.ApiResponseWrapperMetrics
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.netty.buffer.PooledByteBufAllocator
import org.reactivestreams.Publisher
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.core.io.buffer.NettyDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.util.unit.DataSize
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

class ApiResponseWrapperFilterTest :
    DescribeSpec({
        val mapper = JsonMapper.builder().build()
        val factory = DefaultDataBufferFactory.sharedInstance
        lateinit var registry: SimpleMeterRegistry
        lateinit var filter: ApiResponseWrapperFilter

        beforeEach {
            registry = SimpleMeterRegistry()
            filter =
                ApiResponseWrapperFilter(mapper, ApiResponseWrapperProperties(), ApiResponseWrapperMetrics(registry))
        }

        fun exchange(path: String = "/api/roadmap/roadmaps/1/tree") =
            MockServerWebExchange.from(MockServerHttpRequest.get(path))

        fun respond(
            exchange: MockServerWebExchange,
            status: HttpStatus = HttpStatus.OK,
            contentType: MediaType = MediaType.APPLICATION_JSON,
            contentLength: Long? = null,
            body: Publisher<out DataBuffer>,
        ) {
            filter.filter(
                exchange,
                WebFilterChain { forwarded ->
                    forwarded.response.statusCode = status
                    forwarded.response.headers.contentType = contentType
                    if (contentLength != null) forwarded.response.headers.contentLength = contentLength
                    forwarded.response.writeWith(body)
                },
            ).block(Duration.ofSeconds(5))
        }

        fun jsonBody(json: String): Mono<DataBuffer> = Mono.fromSupplier { factory.wrap(json.toByteArray()) }
        fun parsed(exchange: MockServerWebExchange) = mapper.readTree(exchange.response.bodyAsString.block())

        describe("일반 JSON 성공 응답은") {
            for (size in listOf(1024 * 1024 - 1, 1024 * 1024, 1024 * 1024 + 1)) {
                for (knownLength in listOf(false, true)) {
                    context("본문이 ${size}바이트이고 Content-Length 제공 여부가 $knownLength 이면") {
                        it("크기에 관계없이 전체 본문을 data로 감싼다") {
                            val payload = "{\"content\":\"" + "x".repeat(size - 14) + "\"}"
                            val bytes = payload.toByteArray()
                            bytes.size shouldBe size
                            val response = exchange()
                            val buffers = Flux.range(0, (bytes.size + 4095) / 4096).map { index ->
                                factory.wrap(bytes.copyOfRange(index * 4096, minOf((index + 1) * 4096, bytes.size)))
                            }

                            respond(response, contentLength = if (knownLength) size.toLong() else null, body = buffers)

                            val result = parsed(response)
                            result.get("status").asString() shouldBe "200 OK"
                            result.get("code").asInt() shouldBe 200
                            result.get("data") shouldBe mapper.readTree(payload)
                            response.response.headers.contentLength shouldBe -1
                            registry.find(
                                "gateway.response.wrapper.bytes",
                            ).tags("direction", "source", "outcome", "wrapped")
                                .summary()?.totalAmount() shouldBe size.toDouble()
                            registry.find(
                                "gateway.response.wrapper.bytes",
                            ).tags("direction", "result", "outcome", "wrapped")
                                .summary()?.totalAmount() shouldBe
                                response.response.bodyAsString.block()!!.toByteArray().size.toDouble()
                        }
                    }
                }
            }

            for (payload in listOf(
                "[1,2]",
                "\"한글😀\"",
                "42",
                "true",
                "null",
                "{\"status\":\"200 OK\",\"code\":200,\"message\":\"업무 값\"}",
            )) {
                it("JSON 값 $payload 의 바이트 경계와 업무 필드를 보존한다") {
                    val response = exchange()
                    val buffers = Flux.fromIterable(payload.toByteArray().toList()).map {
                        factory.wrap(byteArrayOf(it))
                    }

                    respond(response, HttpStatus.CREATED, body = buffers)

                    response.response.statusCode shouldBe HttpStatus.CREATED
                    parsed(response).get("code").asInt() shouldBe 201
                    parsed(response).get("data") shouldBe mapper.readTree(payload)
                }
            }

            it("변환 후 원본의 길이와 무결성 헤더를 제거한다") {
                val response = exchange()
                response.response.headers.set(HttpHeaders.ETAG, "\"original\"")
                response.response.headers.set("Digest", "original")
                response.response.headers.set("Content-MD5", "original")

                respond(response, contentLength = 2, body = jsonBody("{}"))

                listOf(HttpHeaders.CONTENT_LENGTH, HttpHeaders.ETAG, "Digest", "Content-MD5").forEach {
                    response.response.headers.getFirst(it) shouldBe null
                }
            }

            for (emittedCount in listOf(1L, 2L)) {
                it("upstream 완료 전 전송하며 $emittedCount 개 버퍼 뒤 취소되면 보유 버퍼를 해제한다") {
                    val response = exchange()
                    val pooled = NettyDataBufferFactory(
                        PooledByteBufAllocator.DEFAULT,
                    ).allocateBuffer().write("[1,".toByteArray())
                    val emitted = mutableListOf<String>()
                    response.response.setWriteHandler { body ->
                        body.take(emittedCount).doOnNext {
                            emitted += it.toString(Charsets.UTF_8)
                            DataBufferUtils.release(it)
                        }.then()
                    }
                    val source = Flux.concat(Mono.just(pooled), Flux.never<DataBuffer>())

                    respond(response, body = source)

                    emitted.size shouldBe emittedCount.toInt()
                    emitted.first().endsWith("\"data\":") shouldBe true
                    pooled.nativeBuffer.refCnt() shouldBe 0
                    registry.find(
                        "gateway.response.wrapper.requests",
                    ).tag("outcome", "cancelled").counter()?.count() shouldBe
                        1.0
                }
            }

            it("중간 전송 오류를 정상 JSON 종료로 숨기지 않고 버퍼를 해제한다") {
                val response = exchange()
                val pooled = NettyDataBufferFactory(
                    PooledByteBufAllocator.DEFAULT,
                ).allocateBuffer().write("[1,".toByteArray())
                val emitted = mutableListOf<String>()
                response.response.setWriteHandler { body ->
                    body.doOnNext {
                        emitted += it.toString(Charsets.UTF_8)
                        DataBufferUtils.release(it)
                    }.then()
                }

                shouldThrow<IllegalStateException> {
                    respond(
                        response,
                        body = Flux.concat(Mono.just(pooled), Mono.error(IllegalStateException("upstream failed"))),
                    )
                }

                emitted.size shouldBe 2
                emitted.last() shouldBe "[1,"
                pooled.nativeBuffer.refCnt() shouldBe 0
                registry.find("gateway.response.wrapper.requests").tag("outcome", "error").counter()?.count() shouldBe
                    1.0
            }
        }

        describe("오류 응답은") {
            it("실제 HTTP 상태와 오류 메시지 및 data를 사용한다") {
                val response = exchange()
                respond(
                    response,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    body = jsonBody(
                        """{"status":"200 OK","code":200,"message":"점검 중입니다.","data":{"retryAfterSeconds":3}}""",
                    ),
                )

                val result = parsed(response)
                result.get("status").asString() shouldBe "503 SERVICE_UNAVAILABLE"
                result.get("code").asInt() shouldBe 503
                result.get("message").asString() shouldBe "점검 중입니다."
                result.get("data").get("retryAfterSeconds").asInt() shouldBe 3
            }

            for (body in listOf("""{"error":"권한이 없습니다."}""", "\"권한이 없습니다.\"", "권한이 없습니다.")) {
                it("오류 메시지 $body 를 공통 응답으로 정리한다") {
                    val response = exchange()
                    respond(response, HttpStatus.FORBIDDEN, MediaType.TEXT_PLAIN, body = jsonBody(body))
                    parsed(response).get("message").asString() shouldBe "권한이 없습니다."
                    parsed(response).has("data") shouldBe false
                }
            }

            it("오류 본문 한도를 넘으면 원문 대신 같은 상태의 공통 오류를 반환하고 버퍼를 해제한다") {
                filter = ApiResponseWrapperFilter(
                    mapper,
                    ApiResponseWrapperProperties().apply { maxErrorBodySize = DataSize.ofBytes(16) },
                    ApiResponseWrapperMetrics(registry),
                )
                val response = exchange()
                val pooled = NettyDataBufferFactory(
                    PooledByteBufAllocator.DEFAULT,
                ).allocateBuffer().write("x".repeat(17).toByteArray())

                respond(response, HttpStatus.BAD_GATEWAY, body = Mono.just(pooled))

                parsed(response).get("code").asInt() shouldBe 502
                parsed(response).get("message").asString() shouldBe "Bad Gateway"
                pooled.nativeBuffer.refCnt() shouldBe 0
                registry.find(
                    "gateway.response.wrapper.requests",
                ).tag("outcome", "error_body_too_large").counter()?.count() shouldBe
                    1.0
            }

            for (writeEmpty in listOf(false, true)) {
                it("본문 없는 429를 공통 오류로 채운다: writeWith=$writeEmpty") {
                    val response = exchange()
                    filter.filter(
                        response,
                        WebFilterChain { forwarded ->
                            forwarded.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
                            if (writeEmpty) {
                                forwarded.response.writeWith(
                                    Flux.empty(),
                                )
                            } else {
                                forwarded.response.setComplete()
                            }
                        },
                    ).block()
                    parsed(response).get("message").asString() shouldBe "요청이 너무 많습니다. 잠시 후 다시 시도하십시오."
                }
            }
        }

        describe("래핑 제외 응답은") {
            for (path in listOf(
                "/api/storage/teams/object",
                "/v3/api-docs/roadmap",
                "/internal/deployment-state",
                "/api/chat/graphql",
            )) {
                it("$path 의 본문과 길이를 유지한다") {
                    val response = exchange(path)
                    respond(response, contentLength = 2, body = jsonBody("{}"))
                    response.response.bodyAsString.block() shouldBe "{}"
                    response.response.headers.contentLength shouldBe 2L
                }
            }
            for (status in listOf(HttpStatus.OK, HttpStatus.NO_CONTENT, HttpStatus.NOT_MODIFIED)) {
                it("본문 없는 $status 에 data를 만들지 않는다") {
                    val response = exchange()
                    respond(response, status, body = Flux.empty())
                    response.response.bodyAsString.block() shouldBe ""
                }
            }
            it("HEAD 오류 응답에 본문을 만들지 않는다") {
                val response = MockServerWebExchange.from(MockServerHttpRequest.head("/api/roadmap/roadmaps/1/tree"))
                filter.filter(
                    response,
                    WebFilterChain { forwarded ->
                        forwarded.response.statusCode = HttpStatus.NOT_FOUND
                        forwarded.response.setComplete()
                    },
                ).block()
                response.response.bodyAsString.block() shouldBe ""
            }
            it("SSE flush 경계를 유지한다") {
                val response = exchange("/api/notification/notifications/stream")
                filter.filter(
                    response,
                    WebFilterChain { forwarded ->
                        forwarded.response.headers.contentType = MediaType.TEXT_EVENT_STREAM
                        forwarded.response.writeAndFlushWith(Flux.just(jsonBody("data: hello\n\n")))
                    },
                ).block()
                response.response.bodyAsString.block() shouldBe "data: hello\n\n"
            }
        }
    })
