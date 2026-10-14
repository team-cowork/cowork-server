package com.cowork.gateway.security.filter

import com.cowork.gateway.security.config.DevOriginProperties
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.cloud.gateway.config.GlobalCorsProperties
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.net.URI

class DevOriginCredentialFilterTest :
    DescribeSpec({

        val cors = GlobalCorsProperties().apply {
            corsConfigurations["/**"] = CorsConfiguration().apply {
                allowedOrigins = listOf("http://localhost:3000")
                allowCredentials = true
            }
        }
        val filter = DevOriginCredentialFilter(DevOriginProperties("secret"), cors)

        fun forwardingChain(): Pair<WebFilterChain, CapturingSlot<ServerWebExchange>> {
            val chain = mockk<WebFilterChain>()
            val captured = slot<ServerWebExchange>()
            every { chain.filter(capture(captured)) } returns Mono.empty()
            return chain to captured
        }

        describe("DevOriginCredentialFilter 클래스의 filter 메서드는") {
            context("CORS 허용 origin의 요청에 자격증명이 없으면") {
                it("브라우저가 읽을 수 있는 FORBIDDEN으로 거부한다") {
                    val chain = mockk<WebFilterChain>()
                    val exchange = MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/users/me").header("Origin", "http://localhost:3000"),
                    )

                    filter.filter(exchange, chain).block()

                    exchange.response.statusCode shouldBe HttpStatus.FORBIDDEN
                    exchange.response.headers.accessControlAllowOrigin shouldBe "http://localhost:3000"
                    exchange.response.headers.accessControlAllowCredentials shouldBe true
                    verify(exactly = 0) { chain.filter(any()) }
                }
            }

            context("Gateway가 서빙한 같은 출처 페이지의 요청이면") {
                it("자격증명 없이 전달한다") {
                    val (chain, _) = forwardingChain()
                    val exchange = MockServerWebExchange.from(
                        MockServerHttpRequest
                            .method(HttpMethod.POST, URI.create("http://localhost:8080/api/users"))
                            .header("Origin", "http://localhost:8080"),
                    )

                    filter.filter(exchange, chain).block()

                    verify(exactly = 1) { chain.filter(exchange) }
                }
            }

            context("loopback HTTP origin 요청의 자격증명이 다르면") {
                it("FORBIDDEN으로 거부한다") {
                    val chain = mockk<WebFilterChain>()
                    val exchange = MockServerWebExchange.from(
                        MockServerHttpRequest.get(
                            "/ws/chat?dev_credential=wrong",
                        ).header("Origin", "http://127.0.0.1:5173"),
                    )

                    filter.filter(exchange, chain).block()

                    exchange.response.statusCode shouldBe HttpStatus.FORBIDDEN
                    exchange.response.headers.accessControlAllowOrigin shouldBe null
                    verify(exactly = 0) { chain.filter(any()) }
                }
            }

            context("loopback HTTP origin 요청의 자격증명이 일치하면") {
                it("자격증명 query만 제거하고 나머지 query는 원문 그대로 전달한다") {
                    val (chain, captured) = forwardingChain()
                    val exchange = MockServerWebExchange.from(
                        MockServerHttpRequest
                            .method(
                                HttpMethod.PUT,
                                URI.create(
                                    "/api/storage/bucket/key?X-Amz-Signature=a%2Fb&dev_credential=secret&X-Amz-Date=1",
                                ),
                            )
                            .header("Origin", "http://localhost:3000"),
                    )

                    filter.filter(exchange, chain).block()

                    captured.captured.request.uri.rawQuery shouldBe "X-Amz-Signature=a%2Fb&X-Amz-Date=1"
                }
            }

            context("다른 query 값에 인코딩되지 않은 '='가 있으면") {
                it("원문을 유지한 채 자격증명 query만 제거한다") {
                    val (chain, captured) = forwardingChain()
                    val exchange = MockServerWebExchange.from(
                        MockServerHttpRequest
                            .method(HttpMethod.GET, URI.create("/api/files?token=YWJj==&dev_credential=secret"))
                            .header("Origin", "http://localhost:3000"),
                    )

                    filter.filter(exchange, chain).block()

                    captured.captured.request.uri.rawQuery shouldBe "token=YWJj=="
                }
            }

            context("CORS preflight 요청이면") {
                it("자격증명 없이 전달한다") {
                    val (chain, _) = forwardingChain()
                    val exchange = MockServerWebExchange.from(
                        MockServerHttpRequest.options("/api/users/me").header("Origin", "http://localhost:3000"),
                    )

                    filter.filter(exchange, chain).block()

                    verify(exactly = 1) { chain.filter(exchange) }
                }
            }

            context("운영 HTTPS origin이나 Origin이 없는 요청이면") {
                it("자격증명 없이 전달한다") {
                    listOf(
                        MockServerHttpRequest.get("/api/users/me").header("Origin", "https://service.example"),
                        MockServerHttpRequest.get("/ws/chat"),
                    ).forEach { request ->
                        val (chain, _) = forwardingChain()
                        val exchange = MockServerWebExchange.from(request)

                        filter.filter(exchange, chain).block()

                        verify(exactly = 1) { chain.filter(exchange) }
                    }
                }
            }

            context("loopback이 아닌 origin에서 자격증명 query가 오면") {
                it("검증 없이 query만 제거한다") {
                    val (chain, captured) = forwardingChain()
                    val exchange = MockServerWebExchange.from(
                        MockServerHttpRequest.get(
                            "/api/users/me?dev_credential=any",
                        ).header("Origin", "https://service.example"),
                    )

                    filter.filter(exchange, chain).block()

                    captured.captured.request.uri.rawQuery shouldBe null
                }
            }
        }
    })
