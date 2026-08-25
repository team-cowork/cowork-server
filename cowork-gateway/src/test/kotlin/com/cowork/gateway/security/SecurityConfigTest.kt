package com.cowork.gateway.security

import io.kotest.core.spec.style.DescribeSpec
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.WebFilterChainProxy
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebHandler

class SecurityConfigTest :
    DescribeSpec({
        val authenticationSupport =
            JwtAuthenticationSupport(
                JwtProperties("test-only-secret-key-that-is-long-enough"),
            )
        val securityConfig =
            SecurityConfig(
                JwtServerAuthenticationConverter(authenticationSupport),
                JwtReactiveAuthenticationManager(),
                authenticationSupport,
                WsProperties(),
            )
        val securityChain = securityConfig.securityWebFilterChain(ServerHttpSecurity.http())
        val client =
            WebTestClient
                .bindToWebHandler(
                    WebHandler { exchange ->
                        exchange.response.statusCode = HttpStatus.OK
                        exchange.response.setComplete()
                    },
                ).webFilter(WebFilterChainProxy(securityChain))
                .build()

        describe("Gateway HTTP 보안 체인은") {
            context("canonical 공개 API를 호출하면") {
                listOf(
                    HttpMethod.POST to "/api/authorization/auth/token",
                    HttpMethod.POST to "/api/authorization/auth/refresh",
                    HttpMethod.POST to "/api/authorization/events/datagsm",
                    HttpMethod.POST to "/api/voice/voice/webhook",
                    HttpMethod.GET to "/api/channel/channels/oauth/callback/github",
                ).forEach { (method, path) ->
                    it("$method $path 요청을 JWT 없이 허용한다") {
                        client
                            .method(method)
                            .uri(path)
                            .exchange()
                            .expectStatus()
                            .isOk
                    }
                }
            }

            context("공개 API와 path 또는 method가 다르면") {
                listOf(
                    HttpMethod.GET to "/api/authorization/auth/token",
                    HttpMethod.GET to "/api/authorization/events/datagsm",
                    HttpMethod.GET to "/api/voice/voice/webhook",
                    HttpMethod.POST to "/api/channel/channels/oauth/callback/github",
                    HttpMethod.POST to "/api/events/datagsm",
                ).forEach { (method, path) ->
                    it("$method $path 요청에 JWT를 요구한다") {
                        client
                            .method(method)
                            .uri(path)
                            .exchange()
                            .expectStatus()
                            .isUnauthorized
                    }
                }
            }
        }
    })
