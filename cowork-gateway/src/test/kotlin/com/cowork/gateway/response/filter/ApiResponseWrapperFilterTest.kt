package com.cowork.gateway.response.filter

import com.cowork.gateway.response.config.ApiResponseWrapperProperties
import com.cowork.gateway.response.metrics.ApiResponseWrapperMetrics
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper

class ApiResponseWrapperFilterTest :
    DescribeSpec({
        val objectMapper = JsonMapper.builder().build()
        val registry = SimpleMeterRegistry()
        val filter = ApiResponseWrapperFilter(
            objectMapper,
            ApiResponseWrapperProperties(),
            ApiResponseWrapperMetrics(registry),
        )

        fun completeWith(status: HttpStatus): MockServerWebExchange {
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/user/users/me"))
            val chain = mockk<WebFilterChain>()
            every { chain.filter(any()) } answers {
                val forwarded = firstArg<ServerWebExchange>()
                forwarded.response.statusCode = status
                forwarded.response.setComplete()
            }
            filter.filter(exchange, chain).block()
            return exchange
        }

        fun respondWith(status: HttpStatus, json: String): MockServerWebExchange {
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/user/users/me"))
            val chain = mockk<WebFilterChain>()
            every { chain.filter(any()) } answers {
                val response = firstArg<ServerWebExchange>().response
                response.statusCode = status
                response.headers.contentType = MediaType.APPLICATION_JSON
                response.writeWith(Mono.just(response.bufferFactory().wrap(json.toByteArray())))
            }
            filter.filter(exchange, chain).block()
            return exchange
        }

        fun bodyOf(exchange: MockServerWebExchange) = objectMapper.readTree(exchange.response.bodyAsString.block())

        describe("ApiResponseWrapperFilter 클래스의 filter 메서드는") {
            context("요청 제한기가 429를 빈 본문으로 끝내면") {
                it("공통 오류 응답 본문을 JSON으로 채운다") {
                    val exchange = completeWith(HttpStatus.TOO_MANY_REQUESTS)

                    val body = objectMapper.readTree(exchange.response.bodyAsString.block())
                    exchange.response.headers.contentType shouldBe MediaType.APPLICATION_JSON
                    body.get("status").asString() shouldBe "429 TOO_MANY_REQUESTS"
                    body.get("code").asInt() shouldBe 429
                    body.get("message").asString() shouldBe "요청이 너무 많습니다. 잠시 후 다시 시도하십시오."
                    registry.find("gateway.response.wrapper.requests")
                        .tags("outcome", "wrapped", "status", "4xx")
                        .counter()?.count() shouldBe 1.0
                }
            }

            context("403을 빈 본문으로 끝내면") {
                it("SDK 오류 응답 본문을 채운다") {
                    val exchange = completeWith(HttpStatus.FORBIDDEN)

                    val body = objectMapper.readTree(exchange.response.bodyAsString.block())
                    body.get("status").asString() shouldBe "403 FORBIDDEN"
                    body.get("code").asInt() shouldBe 403
                }
            }

            context("이미 공통 응답으로 감싼 본문을 받으면") {
                it("중첩하지 않고 message와 data를 유지한다") {
                    val body = bodyOf(
                        respondWith(
                            HttpStatus.OK,
                            """{"status":"200 OK","code":200,"message":"처리했습니다.","data":{"id":1}}""",
                        ),
                    )

                    body.get("message").asString() shouldBe "처리했습니다."
                    body.get("data").get("id").asInt() shouldBe 1
                    body.get("data").has("data") shouldBe false
                    registry.find("gateway.response.wrapper.requests")
                        .tags("outcome", "already_wrapped")
                        .counter()?.count() shouldBe 1.0
                }

                it("status가 상태 이름만 있는 형식이어도 감싼 본문으로 판단한다") {
                    val body = bodyOf(
                        respondWith(
                            HttpStatus.TOO_MANY_REQUESTS,
                            """{"status":"TOO_MANY_REQUESTS","code":429,"message":"잠시 후 다시 시도하십시오.","data":{"retryAfterSeconds":3}}""",
                        ),
                    )

                    body.get("status").asString() shouldBe "429 TOO_MANY_REQUESTS"
                    body.get("code").asInt() shouldBe 429
                    body.get("data").get("retryAfterSeconds").asInt() shouldBe 3
                }

                it("본문의 상태가 달라도 실제 HTTP 상태로 응답한다") {
                    val body = bodyOf(
                        respondWith(
                            HttpStatus.BAD_REQUEST,
                            """{"status":"200 OK","code":200,"message":"요청이 잘못되었습니다."}""",
                        ),
                    )

                    body.get("status").asString() shouldBe "400 BAD_REQUEST"
                    body.get("code").asInt() shouldBe 400
                    body.get("message").asString() shouldBe "요청이 잘못되었습니다."
                }
            }

            context("공통 응답과 키 이름만 겹치는 성공 본문을 받으면") {
                it("다른 필드가 있으면 본문 전체를 data로 감싼다") {
                    val body = bodyOf(
                        respondWith(
                            HttpStatus.OK,
                            """{"status":"200 OK","code":200,"message":"안내","reason":"유지할 값"}""",
                        ),
                    )

                    body.get("message").asString() shouldBe "OK"
                    body.get("data").get("reason").asString() shouldBe "유지할 값"
                }

                it("code가 숫자가 아니면 본문 전체를 data로 감싼다") {
                    val body = bodyOf(
                        respondWith(HttpStatus.OK, """{"status":"ACTIVE","code":"A-1","message":"정상"}"""),
                    )

                    body.get("code").asInt() shouldBe 200
                    body.get("data").get("code").asString() shouldBe "A-1"
                }

                it("status와 code가 서로 맞지 않으면 본문 전체를 data로 감싼다") {
                    val body = bodyOf(
                        respondWith(HttpStatus.OK, """{"status":"200 OK","code":404,"message":"안내"}"""),
                    )

                    body.get("code").asInt() shouldBe 200
                    body.get("data").get("code").asInt() shouldBe 404
                }
            }

            context("감싸지 않은 오류 본문을 받으면") {
                it("message를 공통 오류 응답으로 옮긴다") {
                    val body = bodyOf(respondWith(HttpStatus.FORBIDDEN, """{"message":"권한이 없습니다."}"""))

                    body.get("status").asString() shouldBe "403 FORBIDDEN"
                    body.get("message").asString() shouldBe "권한이 없습니다."
                    body.has("data") shouldBe false
                }
            }
        }
    })
