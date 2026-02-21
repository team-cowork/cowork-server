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
 * 외부 서비스가 웹훅으로 보내는 메시지를 웹훅마다 분당 횟수로 제한한다.
 */
@Component
class WebhookRateLimitFilter(
    private val objectMapper: ObjectMapper,
    clock: Clock,
    @Value("\${cowork.rate-limit.webhook-per-minute:60}") limitPerMinute: Int,
) : OncePerRequestFilter() {

    private val limiter = RateLimiter(limitPerMinute, Duration.ofMinutes(1), clock)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != "POST" || webhookId(request.requestURI) == null

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (limiter.tryAcquire(webhookId(request.requestURI)!!)) {
            filterChain.doFilter(request, response)
            return
        }
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.writer, ErrorResponse(429, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."))
    }

    private fun webhookId(uri: String): String? = WEBHOOK_PATH.matchEntire(uri)?.groupValues?.get(1)

    @Scheduled(fixedDelayString = "PT1M")
    fun evictExpired() = limiter.evictExpired()

    companion object {
        private val WEBHOOK_PATH = Regex("^/webhooks/(\\d+)/[^/]+$")
    }
}
