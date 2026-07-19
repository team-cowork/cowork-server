package com.cowork.gateway.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

/**
 * JWT 문자열을 검증하고 [Authentication]으로 변환하는 공통 로직.
 * 토큰을 어디서 추출할지(헤더/쿠키 등)는 각 `ServerAuthenticationConverter`가 담당하고,
 * 이 클래스는 "유효한 토큰 문자열이 주어졌을 때 어떻게 파싱할지"만 책임진다.
 */
@Component
class JwtAuthenticationSupport(private val jwtProperties: JwtProperties) {

    private val signingKey by lazy {
        Keys.hmacShaKeyFor(jwtProperties.secret.toByteArray(StandardCharsets.UTF_8))
    }

    fun parse(token: String): Authentication {
        val claims = Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .payload

        val userId = claims.subject
        val role = claims.get("role", String::class.java) ?: "USER"
        val authorities = listOf(RoleGrantedAuthority(role))

        val auth = UsernamePasswordAuthenticationToken(userId, token, authorities)
        auth.details = mapOf("userId" to userId, "role" to role)
        return auth
    }
}
