package com.cowork.gateway.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets

@Component
class JwtServerAuthenticationConverter(
    private val jwtProperties: JwtProperties
) : ServerAuthenticationConverter {

    private val signingKey by lazy {
        Keys.hmacShaKeyFor(jwtProperties.secret.toByteArray(StandardCharsets.UTF_8))
    }

    override fun convert(exchange: ServerWebExchange): Mono<Authentication> {
        val token = extractToken(exchange) ?: return Mono.empty()

        return runCatching {
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
            auth as Authentication
        }.fold(
            onSuccess = { Mono.just(it) },
            onFailure = { Mono.empty() }
        )
    }

    private fun extractToken(exchange: ServerWebExchange): String? {
        val authHeader = exchange.request.headers.getFirst("Authorization")?.trim()
            ?: return null

        val bearerPrefix = "Bearer "
        if (authHeader.startsWith(bearerPrefix, ignoreCase = true)) {
            return authHeader.substring(bearerPrefix.length).trim().ifEmpty { null }
        }

        return authHeader.takeIf { it.count { ch -> ch == '.' } == 2 }
    }
}
