package com.cowork.gateway.filter

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Configuration
class RateLimitConfig {

    /**
     * Rate Limiting 키 전략:
     * 인증된 요청 → X-User-Id 기준
     * 미인증 요청  → 클라이언트 IP 기준 (fallback)
     */
    @Bean
    fun userKeyResolver(): KeyResolver = KeyResolver { exchange: ServerWebExchange ->
        val userId = exchange.request.headers.getFirst("X-User-Id")
        if (!userId.isNullOrBlank()) {
            Mono.just(userId)
        } else {
            val ip = exchange.request.remoteAddress?.address?.hostAddress ?: "unknown"
            Mono.just(ip)
        }
    }
}
