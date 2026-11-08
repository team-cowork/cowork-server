package com.cowork.channel.global.logging

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher
import org.springframework.web.filter.OncePerRequestFilter

/**
 * the-sdk 로깅은 query·cookie·body를 그대로 남기므로 credential과 OAuth code가 오가는 경로는
 * `sdk.logging.not-logging-urls`에서 제외했다. 그 경로의 요청 흔적은 값 없이 이 필터가 남긴다.
 */
@Component
class SensitiveRequestLoggingFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val pathMatcher = AntPathMatcher()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        SENSITIVE_PATHS.none { pathMatcher.match(it, request.requestURI) }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val startedAt = System.nanoTime()
        try {
            filterChain.doFilter(request, response)
        } finally {
            log.info(
                "Handle request without query and body [method={}, uri={}, status={}, elapsedMs={}]",
                request.method,
                request.requestURI,
                response.status,
                (System.nanoTime() - startedAt) / 1_000_000,
            )
        }
    }

    companion object {
        // application.yml의 sdk.logging.not-logging-urls와 같은 패턴을 유지한다.
        private val SENSITIVE_PATHS = listOf("/channels/*/accounts/**", "/channels/oauth/callback/**")
    }
}
