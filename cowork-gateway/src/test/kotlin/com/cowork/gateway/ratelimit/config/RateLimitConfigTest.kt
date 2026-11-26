package com.cowork.gateway.ratelimit.config

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import java.net.InetSocketAddress

class RateLimitConfigTest :
    DescribeSpec({
        val resolver = RateLimitConfig().agentTokenExchangeKeyResolver()

        fun keyFor(remoteAddress: InetSocketAddress?, forwardedFor: String? = null): String? {
            val request = MockServerHttpRequest.post("/api/authorization/auth/agent-tokens/exchange")
            remoteAddress?.let { request.remoteAddress(it) }
            forwardedFor?.let { request.header("X-Forwarded-For", it) }
            return resolver.resolve(MockServerWebExchange.from(request)).block()
        }

        describe("RateLimitConfig 클래스의 agentTokenExchangeKeyResolver는") {
            context("연결 상대 주소가 다르면") {
                it("주소마다 다른 키를 반환한다") {
                    keyFor(InetSocketAddress("192.0.2.10", 50000)) shouldBe "agent-token-exchange:192.0.2.10"
                    keyFor(InetSocketAddress("192.0.2.11", 50000)) shouldBe "agent-token-exchange:192.0.2.11"
                }
            }

            context("클라이언트가 X-Forwarded-For를 보내면") {
                it("헤더를 무시하고 연결 상대 주소를 쓴다") {
                    keyFor(InetSocketAddress("192.0.2.10", 50000), forwardedFor = "198.51.100.7") shouldBe
                        "agent-token-exchange:192.0.2.10"
                }
            }

            context("연결 상대 주소를 알 수 없으면") {
                it("unknown 키를 반환한다") {
                    keyFor(null) shouldBe "agent-token-exchange:unknown"
                }
            }
        }
    })
