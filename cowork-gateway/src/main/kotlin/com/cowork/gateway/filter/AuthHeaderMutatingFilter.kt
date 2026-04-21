package com.cowork.gateway.filter

import org.slf4j.MDC
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class AuthHeaderMutatingFilter : GlobalFilter, Ordered {

    override fun getOrder(): Int = -1

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        return ReactiveSecurityContextHolder.getContext()
            .map { it.authentication }
            .filter { it != null && it.isAuthenticated }
            .flatMap { auth ->
                @Suppress("UNCHECKED_CAST")
                val details = auth.details as? Map<String, String>
                val userId = details?.get("userId") ?: auth.name
                val role = details?.get("role") ?: auth.authorities.firstOrNull()?.authority ?: ""
                val teamId = exchange.request.headers.getFirst("X-Team-Id") ?: ""

                val mutatedRequest = exchange.request.mutate()
                    .headers { h ->
                        h.set("X-User-Id", userId)
                        h.set("X-User-Role", role)
                    }
                    .build()

                chain.filter(exchange.mutate().request(mutatedRequest).build())
                    .doOnEach { signal ->
                        // 스레드 전환 후에도 Reactor Context → MDC 복원
                        val ctx = signal.contextView
                        if (ctx.hasKey(MDC_USER_ID)) MDC.put(MDC_USER_ID, ctx.get(MDC_USER_ID))
                        if (ctx.hasKey(MDC_TEAM_ID)) MDC.put(MDC_TEAM_ID, ctx.get(MDC_TEAM_ID))
                    }
                    .doFinally {
                        MDC.remove(MDC_USER_ID)
                        MDC.remove(MDC_TEAM_ID)
                    }
                    .contextWrite { ctx ->
                        var c = ctx.put(MDC_USER_ID, userId)
                        if (teamId.isNotEmpty()) c = c.put(MDC_TEAM_ID, teamId)
                        c
                    }
            }
            .switchIfEmpty(chain.filter(exchange))
    }

    companion object {
        private const val MDC_USER_ID = "userId"
        private const val MDC_TEAM_ID = "teamId"
    }
}
