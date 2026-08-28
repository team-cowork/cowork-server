package com.cowork.gateway.security

import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.MalformedJwtException
import io.jsonwebtoken.security.Keys
import io.jsonwebtoken.security.SignatureException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.shouldBe
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Date

private const val SECRET = "test-jwt-secret-key-for-gateway-unit-tests-0123456789"
private const val OTHER_SECRET = "another-jwt-secret-key-completely-different-9876543210"

class JwtAuthenticationSupportTest :
    DescribeSpec({
        val jwtProperties = JwtProperties(secret = SECRET)
        val support = JwtAuthenticationSupport(jwtProperties)

        fun signingKey(secret: String) = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))

        fun tokenFor(
            subject: String,
            role: String? = "ADMIN",
            secret: String = SECRET,
            expiration: Date? = Date.from(Instant.now().plusSeconds(3600)),
        ): String {
            val builder = Jwts.builder().subject(subject)
            if (role != null) builder.claim("role", role)
            if (expiration != null) builder.expiration(expiration)
            return builder.signWith(signingKey(secret)).compact()
        }

        describe("JwtAuthenticationSupport 클래스의") {
            describe("parse 메서드는") {
                context("서명이 유효하고 role 클레임을 포함한 토큰이 주어진 경우") {
                    it("subject를 principal로, role을 ROLE_ 권한으로 매핑한 Authentication을 반환한다") {
                        // Given
                        val token = tokenFor(subject = "42", role = "ADMIN")

                        // When
                        val result = support.parse(token)

                        // Then
                        result.name shouldBe "42"
                        result.isAuthenticated shouldBe true
                        result.authorities.map { it.authority } shouldBe listOf("ROLE_ADMIN")
                        (result as UsernamePasswordAuthenticationToken).credentials shouldBe token
                    }

                    it("details에 userId와 role을 함께 담는다") {
                        // Given
                        val token = tokenFor(subject = "7", role = "MEMBER")

                        // When
                        val result = support.parse(token)

                        // Then
                        @Suppress("UNCHECKED_CAST")
                        val details = result.details as Map<String, String>
                        details shouldContain ("userId" to "7")
                        details shouldContain ("role" to "MEMBER")
                    }
                }

                context("role 클레임이 없는 토큰이 주어진 경우") {
                    it("role을 USER로 기본값 처리한다") {
                        // Given
                        val token = tokenFor(subject = "1", role = null)

                        // When
                        val result = support.parse(token)

                        // Then
                        result.authorities.map { it.authority } shouldBe listOf("ROLE_USER")
                    }
                }

                context("role 클레임이 이미 ROLE_ 접두사를 포함하는 경우") {
                    it("접두사를 중복으로 붙이지 않는다") {
                        // Given
                        val token = tokenFor(subject = "1", role = "ROLE_ADMIN")

                        // When
                        val result = support.parse(token)

                        // Then
                        result.authorities.map { it.authority } shouldBe listOf("ROLE_ADMIN")
                    }
                }

                context("다른 키로 서명된 토큰이 주어진 경우") {
                    it("SignatureException을 던진다") {
                        // Given
                        val token = tokenFor(subject = "1", secret = OTHER_SECRET)

                        // When & Then
                        shouldThrow<SignatureException> { support.parse(token) }
                    }
                }

                context("만료된 토큰이 주어진 경우") {
                    it("ExpiredJwtException을 던진다") {
                        // Given
                        val token = tokenFor(
                            subject = "1",
                            expiration = Date.from(Instant.now().minusSeconds(60)),
                        )

                        // When & Then
                        shouldThrow<ExpiredJwtException> { support.parse(token) }
                    }
                }

                context("JWT 형식이 아닌 문자열이 주어진 경우") {
                    it("MalformedJwtException을 던진다") {
                        // Given
                        val notAToken = "this-is-not-a-jwt"

                        // When & Then
                        shouldThrow<MalformedJwtException> { support.parse(notAToken) }
                    }
                }

                context("빈 문자열이 주어진 경우") {
                    it("IllegalArgumentException을 던진다") {
                        // When & Then
                        shouldThrow<IllegalArgumentException> { support.parse("") }
                    }
                }
            }
        }
    })
