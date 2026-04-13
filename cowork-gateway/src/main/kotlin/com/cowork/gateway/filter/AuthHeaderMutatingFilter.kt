package com.cowork.gateway.filter

import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * JWT 검증 후 SecurityContext에서 userId/role을 꺼내
 * 하위 서비스로 X-User-Id, X-User-Role 헤더를 추가한다.
 */
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

                // set()으로 덮어써서 외부에서 조작된 헤더가 흘러가는 것을 방지
                val mutatedRequest = exchange.request.mutate()
                    .headers { h ->
                        h.set("X-User-Id", userId)
                        h.set("X-User-Role", role)
                    }
                    .build()

                chain.filter(exchange.mutate().request(mutatedRequest).build())
            }
            .switchIfEmpty(chain.filter(exchange))
    }
}
