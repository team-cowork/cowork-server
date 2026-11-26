package com.cowork.gateway.ratelimit.config

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import java.net.InetSocketAddress

class RateLimitConfigTest :
    DescribeSpec({
        val resolver = RateLimitConfig().agentTokenExchangeKeyResolver()

        fun keyFor(token: String?, remoteAddress: String = "10.0.0.5"): String? {
            val request = MockServerHttpRequest.post("/api/authorization/auth/agent-tokens/exchange")
                .remoteAddress(InetSocketAddress(remoteAddress, 50000))
            token?.let { request.header("X-Cowork-Agent-Token", it) }
            return resolver.resolve(MockServerWebExchange.from(request)).block()
        }

        describe("RateLimitConfig 클래스의 agentTokenExchangeKeyResolver는") {
            context("에이전트 토큰 헤더가 있으면") {
                it("토큰 원문 대신 SHA-256 해시로 키를 만든다") {
                    keyFor("abc") shouldBe
                        "agent-token:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
                }

                it("같은 토큰은 접속 주소와 관계없이 같은 키를, 다른 토큰은 다른 키를 반환한다") {
                    keyFor("cwa_one", remoteAddress = "10.0.0.5") shouldBe keyFor("cwa_one", remoteAddress = "10.0.0.6")
                    keyFor("cwa_one") shouldNotBe keyFor("cwa_two")
                }
            }

            context("에이전트 토큰 헤더가 없거나 비어 있으면") {
                it("헤더 없는 요청끼리 같은 키를 반환한다") {
                    keyFor(null) shouldBe "agent-token:missing"
                    keyFor("") shouldBe "agent-token:missing"
                }
            }
        }
    })
