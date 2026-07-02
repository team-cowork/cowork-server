package com.cowork.gateway.security

import org.springframework.security.core.Authentication
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class JwtServerAuthenticationConverter(private val jwtAuthenticationSupport: JwtAuthenticationSupport) :
    ServerAuthenticationConverter {

    override fun convert(exchange: ServerWebExchange): Mono<Authentication> {
        val token = extractToken(exchange) ?: return Mono.empty()

        return runCatching { jwtAuthenticationSupport.parse(token) }.fold(
            onSuccess = { Mono.just(it) },
            onFailure = { Mono.empty() },
        )
    }

    private fun extractToken(exchange: ServerWebExchange): String? {
        val authHeader = exchange.request.headers.getFirst("Authorization")?.trim()
        if (authHeader.isNullOrEmpty()) return null

        val bearerPrefix = "Bearer "
        if (authHeader.startsWith(bearerPrefix, ignoreCase = true)) {
            return authHeader.substring(bearerPrefix.length).trim().ifEmpty { null }
        }

        return authHeader.takeIf { it.count { ch -> ch == '.' } == 2 }
    }
}
