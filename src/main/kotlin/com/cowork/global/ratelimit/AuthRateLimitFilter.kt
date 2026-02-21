package com.cowork.global.ratelimit

import com.cowork.global.error.ErrorResponse
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.time.Duration

/**
 * 로그인과 토큰 갱신 요청을 IP마다 분당 횟수로 제한한다.
 */
@Component
class AuthRateLimitFilter(
    private val objectMapper: ObjectMapper,
    clock: Clock,
    @Value("\${cowork.rate-limit.auth-per-minute:20}") limitPerMinute: Int,
) : OncePerRequestFilter() {

    private val limiter = RateLimiter(limitPerMinute, Duration.ofMinutes(1), clock)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != "POST" || request.requestURI !in LIMITED_PATHS

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (limiter.tryAcquire(request.remoteAddr)) {
            filterChain.doFilter(request, response)
            return
        }
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.writer, ErrorResponse(429, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."))
    }

    @Scheduled(fixedDelayString = "PT1M")
    fun evictExpired() = limiter.evictExpired()

    companion object {
        private val LIMITED_PATHS = setOf("/auth/token", "/auth/refresh")
    }
}
