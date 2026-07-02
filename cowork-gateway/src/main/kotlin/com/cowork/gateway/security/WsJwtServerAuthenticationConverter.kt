package com.cowork.gateway.security

import org.springframework.security.core.Authentication
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * `/ws/**` 전용 인증 컨버터. 이 클래스는 [SecurityConfig]의 `/ws` 전용
 * 보안 체인에서만 사용되므로 경로를 다시 확인할 필요가 없다.
 * chat 외 다른 모듈이 웹소켓을 추가하더라도 이 컨버터를 그대로 재사용한다.
 *
 * 네이티브 앱은 `Authorization` 헤더로 그대로 인증하고, 브라우저는 커스텀 헤더를
 * 실을 수 없어 로그인 시 함께 발급한 전용 쿠키(`cowork_ws_token`)로 인증한다.
 */
class WsJwtServerAuthenticationConverter(private val jwtAuthenticationSupport: JwtAuthenticationSupport) :
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
        if (!authHeader.isNullOrEmpty()) {
            val bearerPrefix = "Bearer "
            if (authHeader.startsWith(bearerPrefix, ignoreCase = true)) {
                return authHeader.substring(bearerPrefix.length).trim().ifEmpty { null }
            }
            return authHeader.takeIf { it.count { ch -> ch == '.' } == 2 }
        }

        return exchange.request.cookies.getFirst(WS_COOKIE_NAME)?.value?.trim()?.ifEmpty { null }
    }

    companion object {
        private const val WS_COOKIE_NAME = "cowork_ws_token"
    }
}
