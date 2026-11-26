package com.cowork.gateway.security.filter

import com.cowork.gateway.security.agent.AgentScope
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

class AuthHeaderMutatingFilterTest :
    DescribeSpec({

        val filter = AuthHeaderMutatingFilter()

        fun spoofedExchange() = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/projects")
                .header("X-User-Id", "attacker")
                .header("X-User-Role", "OWNER")
                .header("X-Cowork-Agent", "true"),
        )

        fun forwardedAgentToken(routeId: String, authentication: UsernamePasswordAuthenticationToken?): String? {
            val forwarded = slot<ServerWebExchange>()
            val chain = mockk<GatewayFilterChain>()
            every { chain.filter(capture(forwarded)) } returns Mono.empty()
            val exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/any").header("X-Cowork-Agent-Token", "cwa_secret"),
            )
            exchange.attributes[ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR] =
                Route.async().id(routeId).uri("http://localhost").predicate { true }.build()

            val result = filter.filter(exchange, chain)
            (
                authentication?.let {
                    result.contextWrite(ReactiveSecurityContextHolder.withAuthentication(it))
                } ?: result
                )
                .block()
            return forwarded.captured.request.headers.getFirst("X-Cowork-Agent-Token")
        }

        describe("AuthHeaderMutatingFilter 클래스의 filter 메서드는") {
            context("에이전트 토큰 헤더가 교환 라우트가 아닌 요청에 실리면") {
                it("인증 여부와 관계없이 다운스트림 전달 전에 제거한다") {
                    val login = UsernamePasswordAuthenticationToken("42", "token", emptyList())

                    forwardedAgentToken("chat-service", null) shouldBe null
                    forwardedAgentToken("chat-service", login) shouldBe null
                }
            }

            context("에이전트 토큰 헤더가 교환 라우트 요청에 실리면") {
                it("Authorization에 그대로 전달한다") {
                    forwardedAgentToken(AuthHeaderMutatingFilter.AGENT_TOKEN_EXCHANGE_ROUTE_ID, null) shouldBe
                        "cwa_secret"
                }
            }

            context("인증되지 않은 요청이 내부 사용자 헤더를 위조하면") {
                it("다운스트림 전달 전에 두 헤더를 제거한다") {
                    val forwarded = slot<ServerWebExchange>()
                    val chain = mockk<GatewayFilterChain>()
                    every { chain.filter(capture(forwarded)) } returns Mono.empty()

                    filter.filter(spoofedExchange(), chain).block()

                    forwarded.captured.request.headers.getFirst("X-User-Id") shouldBe null
                    forwarded.captured.request.headers.getFirst("X-User-Role") shouldBe null
                    forwarded.captured.request.headers.getFirst("X-Cowork-Agent") shouldBe null
                }
            }

            context("인증된 요청이 내부 사용자 헤더를 위조하면") {
                it("검증된 인증 정보로 덮어쓴다") {
                    val forwarded = slot<ServerWebExchange>()
                    val chain = mockk<GatewayFilterChain>()
                    every { chain.filter(capture(forwarded)) } returns Mono.empty()
                    val authentication = UsernamePasswordAuthenticationToken("42", "token", emptyList())

                    filter.filter(spoofedExchange(), chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                        .block()

                    forwarded.captured.request.headers.getFirst("X-User-Id") shouldBe "42"
                    forwarded.captured.request.headers.getFirst("X-User-Role") shouldBe null
                    forwarded.captured.request.headers.getFirst("X-Cowork-Agent") shouldBe null
                }
            }

            context("agent JWT로 인증된 요청이면") {
                it("agent 요청 헤더를 붙인다") {
                    val forwarded = slot<ServerWebExchange>()
                    val chain = mockk<GatewayFilterChain>()
                    every { chain.filter(capture(forwarded)) } returns Mono.empty()
                    val authentication = UsernamePasswordAuthenticationToken(
                        "42",
                        "token",
                        listOf(AgentScope.AGENT_AUTHORITY, AgentScope.CHAT_WRITE.authority),
                    )

                    filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/chat")), chain)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                        .block()

                    forwarded.captured.request.headers.getFirst("X-User-Id") shouldBe "42"
                    forwarded.captured.request.headers.getFirst("X-Cowork-Agent") shouldBe "true"
                }
            }
        }
    })
