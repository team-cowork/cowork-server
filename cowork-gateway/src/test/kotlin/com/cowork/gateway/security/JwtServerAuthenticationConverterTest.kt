package com.cowork.gateway.security

import io.jsonwebtoken.MalformedJwtException
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.web.server.ServerWebExchange

class JwtServerAuthenticationConverterTest :
    DescribeSpec({
        val jwtAuthenticationSupport = mockk<JwtAuthenticationSupport>()
        val converter = JwtServerAuthenticationConverter(jwtAuthenticationSupport)

        fun exchangeWithAuthorizationHeader(headerValue: String?): ServerWebExchange {
            val headers = mockk<HttpHeaders>()
            every { headers.getFirst("Authorization") } returns headerValue
            val request = mockk<ServerHttpRequest>()
            every { request.headers } returns headers
            val exchange = mockk<ServerWebExchange>()
            every { exchange.request } returns request
            return exchange
        }

        describe("JwtServerAuthenticationConverter 클래스의") {
            describe("convert 메서드는") {
                context("Authorization 헤더가 Bearer 접두사를 포함하는 경우") {
                    it("접두사를 제거한 토큰으로 파싱을 시도한다") {
                        // Given
                        val exchange = exchangeWithAuthorizationHeader("Bearer abc.def.ghi")
                        val auth = UsernamePasswordAuthenticationToken("1", "abc.def.ghi", emptyList())
                        every { jwtAuthenticationSupport.parse("abc.def.ghi") } returns auth

                        // When
                        val result = converter.convert(exchange).block()

                        // Then
                        result shouldBe auth
                    }
                }

                context("Authorization 헤더가 Bearer 접두사 없이 순수 JWT 형태(점 2개)인 경우") {
                    it("헤더 값 그대로 파싱을 시도한다") {
                        // Given
                        val exchange = exchangeWithAuthorizationHeader("abc.def.ghi")
                        val auth = UsernamePasswordAuthenticationToken("1", "abc.def.ghi", emptyList())
                        every { jwtAuthenticationSupport.parse("abc.def.ghi") } returns auth

                        // When
                        val result = converter.convert(exchange).block()

                        // Then
                        result shouldBe auth
                    }
                }

                context("Authorization 헤더가 없는 경우") {
                    it("파싱을 시도하지 않고 빈 Mono를 반환한다") {
                        // Given
                        val exchange = exchangeWithAuthorizationHeader(null)

                        // When
                        val result = converter.convert(exchange).block()

                        // Then
                        result shouldBe null
                    }
                }

                context("Authorization 헤더가 공백 문자열인 경우") {
                    it("파싱을 시도하지 않고 빈 Mono를 반환한다") {
                        // Given
                        val exchange = exchangeWithAuthorizationHeader("   ")

                        // When
                        val result = converter.convert(exchange).block()

                        // Then
                        result shouldBe null
                    }
                }

                context("Authorization 헤더가 JWT 형태가 아닌 경우(점이 2개가 아님)") {
                    it("파싱을 시도하지 않고 빈 Mono를 반환한다") {
                        // Given
                        val exchange = exchangeWithAuthorizationHeader("not-a-valid-token")

                        // When
                        val result = converter.convert(exchange).block()

                        // Then
                        result shouldBe null
                    }
                }

                context("토큰 파싱 중 서명 검증 등에 실패하는 경우") {
                    it("예외를 전파하지 않고 빈 Mono를 반환한다") {
                        // Given
                        val exchange = exchangeWithAuthorizationHeader("Bearer bad.bad.bad")
                        every { jwtAuthenticationSupport.parse("bad.bad.bad") } throws
                            MalformedJwtException("invalid token")

                        // When
                        val result = converter.convert(exchange).block()

                        // Then
                        result shouldBe null
                    }
                }
            }
        }
    })
