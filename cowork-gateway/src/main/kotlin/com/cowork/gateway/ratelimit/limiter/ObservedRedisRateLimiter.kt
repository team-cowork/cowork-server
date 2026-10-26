package com.cowork.gateway.ratelimit.limiter

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.cloud.gateway.support.ConfigurationService
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.http.HttpHeaders
import reactor.core.publisher.Mono
import kotlin.math.ceil

/**
 * Redis 오류 시 요청을 허용하는 기본 동작(fail-open)은 유지하고, 차단과 제한기 오류를 메트릭으로 구분한다.
 * 차단 응답에는 토큰 1회 요청분이 다시 채워지는 시간을 `Retry-After`로 더한다.
 */
class ObservedRedisRateLimiter(
    redisTemplate: ReactiveStringRedisTemplate,
    script: RedisScript<List<Long>>,
    configurationService: ConfigurationService,
    private val meterRegistry: MeterRegistry,
) : RedisRateLimiter(redisTemplate, script, configurationService) {

    override fun isAllowed(routeId: String, id: String): Mono<RateLimiter.Response> =
        super.isAllowed(routeId, id).map { response ->
            when {
                // RedisRateLimiter는 Redis 호출 실패 시 허용 응답과 남은 토큰 -1을 반환한다
                isRedisError(response) -> response.also { record(routeId, OUTCOME_REDIS_ERROR) }

                !response.isAllowed -> {
                    record(routeId, OUTCOME_REJECTED)
                    val retryAfter = retryAfterSeconds(response.headers)
                    RateLimiter.Response(false, response.headers + (HttpHeaders.RETRY_AFTER to retryAfter.toString()))
                }

                else -> response
            }
        }

    private fun record(routeId: String, outcome: String) {
        Counter.builder(EVENTS_METRIC)
            .tag("route", routeId)
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment()
    }

    companion object {
        private const val EVENTS_METRIC = "gateway.ratelimit.events"
        private const val OUTCOME_REJECTED = "rejected"
        private const val OUTCOME_REDIS_ERROR = "redis_error"

        private fun isRedisError(response: RateLimiter.Response): Boolean =
            response.isAllowed && response.headers[RedisRateLimiter.REMAINING_HEADER] == "-1"

        private fun retryAfterSeconds(headers: Map<String, String>): Long {
            val replenishRate =
                headers[RedisRateLimiter.REPLENISH_RATE_HEADER]?.toDoubleOrNull()?.takeIf { it > 0 } ?: return 1
            val requestedTokens = headers[RedisRateLimiter.REQUESTED_TOKENS_HEADER]?.toDoubleOrNull() ?: 1.0
            return ceil(requestedTokens / replenishRate).toLong().coerceAtLeast(1)
        }
    }
}
