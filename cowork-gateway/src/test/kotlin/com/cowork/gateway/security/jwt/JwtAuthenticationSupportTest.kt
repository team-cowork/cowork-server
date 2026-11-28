package com.cowork.gateway.security.jwt

import com.cowork.gateway.security.agent.AgentScope
import com.cowork.gateway.security.agent.isAgent
import com.cowork.gateway.security.config.JwtProperties
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.MalformedJwtException
import io.jsonwebtoken.security.Keys
import io.jsonwebtoken.security.SignatureException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Date

class JwtAuthenticationSupportTest :
    DescribeSpec({
        val jwtProperties = JwtProperties(secret = SECRET)
        val support = JwtAuthenticationSupport(jwtProperties)

        fun signingKey(secret: String) = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))

        fun tokenFor(
            subject: String,
            secret: String = SECRET,
            expiration: Date? = Date.from(Instant.now().plusSeconds(3600)),
        ): String {
            val builder = Jwts.builder().subject(subject)
            if (expiration != null) builder.expiration(expiration)
            return builder.signWith(signingKey(secret)).compact()
        }

        describe("JwtAuthenticationSupport 클래스의") {
            describe("parse 메서드는") {
                context("서명이 유효한 액세스 토큰이 주어진 경우") {
                    it("subject로 사용자를 인증하고 플랫폼 권한을 부여하지 않는다") {
                        // Given
                        val token = tokenFor(subject = "42")

                        // When
                        val result = support.parse(token)

                        // Then
                        result.name shouldBe "42"
                        result.isAuthenticated shouldBe true
                        result.authorities.isEmpty() shouldBe true
                        (result as UsernamePasswordAuthenticationToken).credentials shouldBe token
                    }
                }

                context("scope가 유효한 agent JWT가 주어진 경우") {
                    it("agent 권한과 scope 권한을 부여한다") {
                        // Given
                        val token = Jwts.builder().subject("42")
                            .claim("token_use", "agent").claim("scope", "chat:read chat:write")
                            .expiration(Date.from(Instant.now().plusSeconds(600)))
                            .signWith(signingKey(SECRET)).compact()

                        // When
                        val result = support.parse(token)

                        // Then
                        result.isAgent() shouldBe true
                        result.authorities.toSet() shouldBe setOf(
                            AgentScope.AGENT_AUTHORITY,
                            AgentScope.CHAT_READ.authority,
                            AgentScope.CHAT_WRITE.authority,
                        )
                    }
                }

                context("scope가 없거나 알 수 없는 agent JWT 또는 알 수 없는 용도의 JWT가 주어진 경우") {
                    it("JwtException을 던진다") {
                        val claimSets = listOf(
                            mapOf("token_use" to "agent"),
                            mapOf("token_use" to "agent", "scope" to ""),
                            mapOf("token_use" to "agent", "scope" to "chat:read admin"),
                            mapOf("token_use" to "agent", "scope" to listOf("chat:read")),
                            mapOf("token_use" to "service"),
                        )
                        claimSets.forEach { claims ->
                            val token = Jwts.builder().subject("42").claims(claims)
                                .expiration(Date.from(Instant.now().plusSeconds(600)))
                                .signWith(signingKey(SECRET)).compact()

                            shouldThrow<JwtException> { support.parse(token) }
                        }
                    }
                }

                context("만료 시각이 있는 토큰이 주어진 경우") {
                    it("검증된 만료 시각을 쿠키 수명 계산에 전달한다") {
                        // Given
                        val expiresAt = Instant.now().plusSeconds(600).epochSecond
                        val token = tokenFor(subject = "42", expiration = Date.from(Instant.ofEpochSecond(expiresAt)))

                        // When
                        val details = support.parse(token).details as Map<*, *>

                        // Then
                        details["expiresAt"] shouldBe expiresAt.toString()
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
    }) {
    private companion object {
        private const val SECRET = "test-jwt-secret-key-for-gateway-unit-tests-0123456789"
        private const val OTHER_SECRET = "another-jwt-secret-key-completely-different-9876543210"
    }
}
