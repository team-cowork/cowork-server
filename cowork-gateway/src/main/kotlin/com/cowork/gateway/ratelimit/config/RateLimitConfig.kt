package com.cowork.gateway.ratelimit.config

import com.cowork.gateway.ratelimit.limiter.ObservedRedisRateLimiter
import com.cowork.gateway.webhook.WebhookUrlSignatureVerifier
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.cloud.gateway.support.ConfigurationService
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Configuration
class RateLimitConfig {

    // 자동 구성의 redisRateLimiter(@ConditionalOnMissingBean)를 대체하며 redis-rate-limiter.* 라우트 설정을 그대로 받는다
    @Bean
    fun redisRateLimiter(
        redisTemplate: ReactiveStringRedisTemplate,
        @Qualifier(RedisRateLimiter.REDIS_SCRIPT_NAME) script: RedisScript<List<Long>>,
        configurationService: ConfigurationService,
        meterRegistry: MeterRegistry,
    ): RedisRateLimiter = ObservedRedisRateLimiter(redisTemplate, script, configurationService, meterRegistry)

    @Bean
    fun webhookKeyResolver(): KeyResolver = KeyResolver { exchange ->
        val raw = ServerWebExchangeUtils.getUriTemplateVariables(exchange)["executionId"]
        val executionId = WebhookUrlSignatureVerifier.canonicalExecutionId(raw)
        Mono.justOrEmpty(executionId)
            .map { "webhook:$it" }
    }

    /**
     * Rate Limiting 키 전략:
     * 인증된 요청 → X-User-Id 기준 (AuthHeaderMutatingFilter가 JWT 검증 결과로 덮어쓰고, 미인증 요청에서는 제거한다)
     * 미인증 요청  → 연결 상대 주소(remoteAddress) 기준
     *
     * shortcut: 현재 입구 구성은 실제 발신 IP를 전달하지 않아 같은 프록시를 거친 미인증 요청이 한도를 공유한다.
     * 클라이언트가 바꿀 수 있는 X-Forwarded-For는 신뢰하지 않는다. 발신 IP를 보존하는 입구가 생기면
     * 그 프록시 범위만 신뢰하도록 바꾼다.
     *
     * 자동 구성에 주입할 기본 KeyResolver를 @Primary로 지정하며, 웹훅 라우트는 webhookKeyResolver를 명시해 사용한다.
     */
    @Bean
    @Primary
    fun userKeyResolver(): KeyResolver = KeyResolver { exchange: ServerWebExchange ->
        val userId = exchange.request.headers.getFirst("X-User-Id")
        if (!userId.isNullOrBlank()) {
            Mono.just(userId)
        } else {
            val ip = exchange.request.remoteAddress?.address?.hostAddress ?: "unknown"
            Mono.just(ip)
        }
    }
}
