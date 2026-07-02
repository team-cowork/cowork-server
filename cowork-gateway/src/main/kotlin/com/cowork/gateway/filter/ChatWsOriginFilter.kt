package com.cowork.gateway.filter

import com.cowork.gateway.security.ChatWsProperties
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * `/chat-ws` 핸드셰이크는 쿠키에 담긴 JWT로 인증되므로, 브라우저가 쿠키를 자동으로
 * 실어 보내는 특성을 악용해 다른 사이트가 사용자 몰래 연결을 시도하는 것을 막기 위해
 * `Origin` 헤더를 허용 목록과 대조한다. 네이티브 앱은 보통 `Origin` 헤더를 보내지 않으므로
 * (Authorization 헤더로 직접 인증) 그 경우는 통과시킨다.
 */
@Component
class ChatWsOriginFilter(private val chatWsProperties: ChatWsProperties) :
    GlobalFilter,
    Ordered {

    override fun getOrder(): Int = -2

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val request = exchange.request
        if (!request.uri.path.startsWith(CHAT_WS_PATH)) {
            return chain.filter(exchange)
        }

        val origin = request.headers.getFirst(HttpHeaders.ORIGIN)
        if (origin != null && origin !in chatWsProperties.allowedOrigins) {
            exchange.response.statusCode = HttpStatus.FORBIDDEN
            return exchange.response.setComplete()
        }

        return chain.filter(exchange)
    }

    companion object {
        private const val CHAT_WS_PATH = "/chat-ws"
    }
}
