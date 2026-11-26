package com.cowork.gateway.security.filter

import com.cowork.gateway.security.agent.AgentScope
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.springframework.cloud.gateway.filter.GatewayFilterChain
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

        describe("AuthHeaderMutatingFilter 클래스의 filter 메서드는") {
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
