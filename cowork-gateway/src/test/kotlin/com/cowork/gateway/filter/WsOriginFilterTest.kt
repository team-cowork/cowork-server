package com.cowork.gateway.filter

import com.cowork.gateway.security.WsProperties
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

class WsOriginFilterTest :
    DescribeSpec({

        describe("WsOriginFilter 클래스의 filter 메서드는") {
            context("브라우저 Origin이 허용 목록에 없으면") {
                it("핸드셰이크를 FORBIDDEN으로 거부한다") {
                    val chain = mockk<WebFilterChain>()
                    val exchange = MockServerWebExchange.from(
                        MockServerHttpRequest.get("/ws/chat").header("Origin", "https://evil.example"),
                    )

                    WsOriginFilter(WsProperties(listOf("https://app.example"))).filter(exchange, chain).block()

                    exchange.response.statusCode shouldBe HttpStatus.FORBIDDEN
                    verify(exactly = 0) { chain.filter(any()) }
                }
            }

            context("브라우저 Origin이 허용 목록에 있으면") {
                it("핸드셰이크를 전달한다") {
                    val chain = mockk<WebFilterChain>()
                    val exchange = MockServerWebExchange.from(
                        MockServerHttpRequest.get("/ws/chat").header("Origin", "https://app.example"),
                    )
                    every { chain.filter(exchange) } returns Mono.empty()

                    WsOriginFilter(WsProperties(listOf("https://app.example"))).filter(exchange, chain).block()

                    verify(exactly = 1) { chain.filter(exchange) }
                }
            }

            context("네이티브 요청처럼 Origin 헤더가 없으면") {
                it("핸드셰이크를 전달한다") {
                    val chain = mockk<WebFilterChain>()
                    val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/ws/chat"))
                    every { chain.filter(exchange) } returns Mono.empty()

                    WsOriginFilter(WsProperties(emptyList())).filter(exchange, chain).block()

                    verify(exactly = 1) { chain.filter(exchange) }
                }
            }
        }
    })
