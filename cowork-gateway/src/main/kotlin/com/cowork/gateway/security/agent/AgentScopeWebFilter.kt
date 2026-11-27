package com.cowork.gateway.security.agent

import org.springframework.http.HttpStatus
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * 인증 직후 agent JWT 요청을 scope 허용 목록으로 검사한다.
 * 인증 없이 열린 경로도 agent JWT가 붙으면 사용자로 전달되므로 경로와 관계없이 모든 요청을 검사한다.
 */
class AgentScopeWebFilter : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> =
        ReactiveSecurityContextHolder.getContext()
            .mapNotNull { it.authentication }
            .map { authentication ->
                authentication.isAgent() &&
                    !AgentScope.allows(
                        authentication,
                        exchange.request.method,
                        exchange.request.path.pathWithinApplication(),
                    )
            }
            .defaultIfEmpty(false)
            .flatMap { denied ->
                if (denied) {
                    exchange.response.statusCode = HttpStatus.FORBIDDEN
                    exchange.response.setComplete()
                } else {
                    chain.filter(exchange)
                }
            }
}
