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
class JwtServerAuthenticationConverter(private val jwtProperties: JwtProperties) : ServerAuthenticationConverter {

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
            onFailure = { Mono.empty() },
        )
    }

    private fun extractToken(exchange: ServerWebExchange): String? {
        val authHeader = exchange.request.headers.getFirst("Authorization")?.trim()
        if (authHeader != null) {
            val bearerPrefix = "Bearer "
            if (authHeader.startsWith(bearerPrefix, ignoreCase = true)) {
                return authHeader.substring(bearerPrefix.length).trim().ifEmpty { null }
            }
            return authHeader.takeIf { it.count { ch -> ch == '.' } == 2 }
        }

        // Socket.IO WebSocket 핸드셰이크는 브라우저 제약상 커스텀 Authorization 헤더를 실을 수 없어
        // 로그인 시 함께 발급한 전용 쿠키로 토큰을 전달한다. 노출 범위를 최소화하기 위해
        // /chat-ws 경로에서만 허용하고, 쿠키 자체도 Path=/chat-ws로 스코프되어 있다.
        if (exchange.request.uri.path.startsWith(CHAT_WS_PATH)) {
            return exchange.request.cookies.getFirst(CHAT_WS_COOKIE_NAME)?.value?.trim()?.ifEmpty { null }
        }

        return null
    }

    companion object {
        private const val CHAT_WS_PATH = "/chat-ws"
        private const val CHAT_WS_COOKIE_NAME = "cowork_ws_token"
    }
}
