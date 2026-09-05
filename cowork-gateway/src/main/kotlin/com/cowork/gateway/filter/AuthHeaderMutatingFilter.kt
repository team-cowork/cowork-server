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
class AuthHeaderMutatingFilter :
    GlobalFilter,
    Ordered {

    override fun getOrder(): Int = -1

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> =
        ReactiveSecurityContextHolder.getContext()
            .mapNotNull { it.authentication }
            .filter { it.isAuthenticated }
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
                    .thenReturn(true)
            }
            .defaultIfEmpty(false)
            .flatMap { authenticated ->
                if (authenticated) {
                    Mono.empty()
                } else {
                    // 인증되지 않은 요청(permitAll 라우트 포함)이 클라이언트가 실은
                    // X-User-Id/X-User-Role을 그대로 들고 다운스트림에 도달하지 못하도록 항상 제거
                    val strippedRequest = exchange.request.mutate()
                        .headers { h ->
                            h.remove("X-User-Id")
                            h.remove("X-User-Role")
                        }
                        .build()
                    chain.filter(exchange.mutate().request(strippedRequest).build())
                }
            }

    companion object {
        private const val MDC_USER_ID = "userId"
        private const val MDC_TEAM_ID = "teamId"
    }
}
