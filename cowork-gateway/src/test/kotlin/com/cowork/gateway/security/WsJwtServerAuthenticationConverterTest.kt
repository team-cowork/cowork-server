package com.cowork.gateway.security

import io.jsonwebtoken.MalformedJwtException
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpCookie
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken

class WsJwtServerAuthenticationConverterTest :
    DescribeSpec({

        val support = mockk<JwtAuthenticationSupport>()
        val converter = WsJwtServerAuthenticationConverter(support)

        fun exchange(header: String? = null, cookie: String? = null): MockServerWebExchange {
            val request = MockServerHttpRequest.get("/ws/chat")
            header?.let { request.header("Authorization", it) }
            cookie?.let { request.cookie(HttpCookie("cowork_ws_token", it)) }
            return MockServerWebExchange.from(request)
        }

        describe("WsJwtServerAuthenticationConverter 클래스의 convert 메서드는") {
            context("Authorization 헤더에 Bearer 토큰이 있으면") {
                it("쿠키보다 헤더 토큰을 우선해 인증한다") {
                    val authentication = UsernamePasswordAuthenticationToken("7", "header.token.value", emptyList())
                    every { support.parse("header.token.value") } returns authentication

                    val result = converter.convert(
                        exchange(header = "Bearer header.token.value", cookie = "cookie.token.value"),
                    ).block()

                    result shouldBe authentication
                }
            }

            context("헤더 없이 웹소켓 쿠키만 있으면") {
                it("쿠키 토큰으로 인증한다") {
                    val authentication = UsernamePasswordAuthenticationToken("7", "cookie.token.value", emptyList())
                    every { support.parse("cookie.token.value") } returns authentication

                    converter.convert(exchange(cookie = "cookie.token.value")).block() shouldBe authentication
                }
            }

            context("토큰이 없거나 형식이 잘못되면") {
                it("인증 없이 빈 결과를 반환한다") {
                    converter.convert(exchange()).block() shouldBe null
                    converter.convert(
                        exchange(
                            header = "not-a-jwt",
                            cookie = "cookie.token.value",
                        ),
                    ).block() shouldBe null
                }
            }

            context("토큰 검증에 실패하면") {
                it("예외를 노출하지 않고 인증을 거부한다") {
                    every { support.parse("bad.bad.bad") } throws MalformedJwtException("invalid token")

                    converter.convert(exchange(cookie = "bad.bad.bad")).block() shouldBe null
                }
            }
        }
    })
