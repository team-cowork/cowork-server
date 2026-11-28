package com.cowork.gateway.ratelimit.config

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import java.net.InetSocketAddress
import java.util.HexFormat
import kotlin.random.Random

class RateLimitConfigTest :
    DescribeSpec({
        val resolver = RateLimitConfig().agentTokenExchangeKeyResolver()

        fun keyFor(token: String?, remoteAddress: String = "10.0.0.5"): String? {
            val request = MockServerHttpRequest.post("/api/authorization/auth/agent-tokens/exchange")
                .remoteAddress(InetSocketAddress(remoteAddress, 50000))
            token?.let { request.header("X-Cowork-Agent-Token", it) }
            return resolver.resolve(MockServerWebExchange.from(request)).block()
        }

        fun randomToken(): String = "cwa_" + HexFormat.of().formatHex(Random.nextBytes(32))

        describe("RateLimitConfig 클래스의 agentTokenExchangeKeyResolver는") {
            context("발급 형식의 토큰이 오면") {
                it("토큰 원문 대신 SHA-256 해시 앞 4자리로 키를 만든다") {
                    keyFor("cwa_" + "a".repeat(64)) shouldBe "agent-token:418d"
                }

                it("같은 토큰은 접속 주소와 관계없이 같은 키를, 해시가 다른 토큰은 다른 키를 반환한다") {
                    val token = "cwa_" + "a".repeat(64)
                    keyFor(token, remoteAddress = "10.0.0.5") shouldBe keyFor(token, remoteAddress = "10.0.0.6")
                    keyFor(token) shouldNotBe keyFor("cwa_" + "b".repeat(64))
                }

                it("무작위 토큰도 해시 앞 4자리 형식의 키를 만든다") {
                    repeat(5) { keyFor(randomToken()) shouldMatch Regex("^agent-token:[0-9a-f]{4}$") }
                }
            }

            context("헤더가 없거나 발급 형식이 아니면") {
                it("모두 같은 공유 키를 반환한다") {
                    listOf(
                        null,
                        "",
                        "abc",
                        "cwa_" + "a".repeat(63),
                        "cwa_" + "A".repeat(64),
                        "cwa_" + "a".repeat(64) + "\n",
                        "xyz_" + "a".repeat(64),
                    ).forEach { keyFor(it) shouldBe "agent-token:invalid" }
                }
            }
        }
    })
