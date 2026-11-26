package com.cowork.gateway.security.agent

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

class AgentScopeWebFilterTest :
    DescribeSpec({
        val filter = AgentScopeWebFilter()

        fun agent(vararg scopes: AgentScope): Authentication = UsernamePasswordAuthenticationToken(
            "42",
            "token",
            listOf(AgentScope.AGENT_AUTHORITY) + scopes.map { it.authority },
        )

        // 요청이 하위 체인까지 전달되었는지와 응답 상태를 반환한다.
        fun run(method: HttpMethod, path: String, authentication: Authentication?): Pair<Boolean, HttpStatus?> {
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, path))
            var forwarded = false
            val chain = WebFilterChain {
                forwarded = true
                Mono.empty()
            }
            val result = filter.filter(exchange, chain)
            val withContext = authentication
                ?.let { result.contextWrite(ReactiveSecurityContextHolder.withAuthentication(it)) }
                ?: result
            withContext.block()
            return forwarded to exchange.response.statusCode?.let { HttpStatus.valueOf(it.value()) }
        }

        describe("AgentScopeWebFilter 클래스의 filter 메서드는") {
            context("scope 허용 목록에 있는 agent 요청이면") {
                it("하위 체인으로 전달한다") {
                    run(HttpMethod.GET, "/api/chat/chat/channels/7/messages", agent(AgentScope.CHAT_READ))
                        .first shouldBe true
                    run(HttpMethod.POST, "/api/chat/chat/channels/7/messages", agent(AgentScope.CHAT_WRITE))
                        .first shouldBe true
                }
            }

            context("scope 허용 목록에 없는 agent 요청이면") {
                it("Forbidden으로 거부한다") {
                    val all = agent(*AgentScope.entries.toTypedArray())
                    val denied = listOf(
                        Triple(HttpMethod.POST, "/api/chat/chat/channels/7/messages", agent(AgentScope.CHAT_READ)),
                        Triple(HttpMethod.GET, "/api/chat/chat/channels/7/messages", agent(AgentScope.CHAT_WRITE)),
                        Triple(HttpMethod.DELETE, "/api/chat/chat/channels/7/messages", all),
                        Triple(HttpMethod.GET, "/api/chat/chat/channels/7/messages/", all),
                        Triple(HttpMethod.GET, "/API/chat/chat/channels/7/messages", all),
                        Triple(HttpMethod.GET, "/api/chat/chat/channels/7/messages/../../x", all),
                        Triple(HttpMethod.GET, "/api/chat/chat/channels/7/messages/1/attachments/2/download-url", all),
                        Triple(HttpMethod.POST, "/api/auth/ws-cookie", all),
                        Triple(HttpMethod.POST, "/api/authorization/auth/agent-tokens", all),
                        Triple(HttpMethod.GET, "/api/storage/bucket/chat-files/a", all),
                    )
                    denied.forEach { (method, path, authentication) ->
                        run(method, path, authentication) shouldBe (false to HttpStatus.FORBIDDEN)
                    }
                }
            }

            context("용도 claim이 없는 로그인 JWT나 미인증 요청이면") {
                it("경로와 관계없이 하위 체인으로 전달한다") {
                    val login = UsernamePasswordAuthenticationToken("42", "token", emptyList())

                    run(HttpMethod.POST, "/api/auth/ws-cookie", login).first shouldBe true
                    run(HttpMethod.DELETE, "/api/team/teams/1", null).first shouldBe true
                }
            }
        }
    })
