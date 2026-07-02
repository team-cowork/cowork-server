package com.cowork.gateway.filter

import com.cowork.gateway.security.WsProperties
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * `/ws/**` 핸드셰이크는 쿠키에 담긴 JWT로 인증되므로, 브라우저가 쿠키를 자동으로
 * 실어 보내는 특성을 악용해 다른 사이트가 사용자 몰래 연결을 시도하는 것을 막기 위해
 * `Origin` 헤더를 허용 목록과 대조한다. 네이티브 앱은 보통 `Origin` 헤더를 보내지 않으므로
 * (Authorization 헤더로 직접 인증) 그 경우는 통과시킨다.
 *
 * `SecurityConfig`의 `/ws` 전용 보안 체인에만 연결되므로 경로를 다시 확인할 필요가 없다.
 * chat 외 다른 모듈이 웹소켓을 추가하더라도 이 필터를 그대로 재사용한다.
 */
class WsOriginFilter(private val wsProperties: WsProperties) : WebFilter {

    private val log = LoggerFactory.getLogger(WsOriginFilter::class.java)

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val origin = exchange.request.headers.getFirst(HttpHeaders.ORIGIN)
        if (origin != null) {
            val allowed = wsProperties.allowedOrigins
            if (!allowed.contains("*") && origin !in allowed) {
                log.warn("Reject WS connection due to unauthorized origin {}", origin)
                exchange.response.statusCode = HttpStatus.FORBIDDEN
                return exchange.response.setComplete()
            }
        }

        return chain.filter(exchange)
    }
}
