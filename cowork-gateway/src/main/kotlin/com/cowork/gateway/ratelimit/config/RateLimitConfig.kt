package com.cowork.gateway.ratelimit.config

import com.cowork.gateway.ratelimit.limiter.ObservedRedisRateLimiter
import com.cowork.gateway.security.filter.AuthHeaderMutatingFilter
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

@Configuration
class RateLimitConfig {

    // 자동 구성의 redisRateLimiter(@ConditionalOnMissingBean)를 대체하며 redis-rate-limiter.* 라우트 설정을 그대로 받는다
    @Bean
    @Primary
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

    // 요청자를 구분할 수 없는 미인증 라우트에 전체 상한을 두는 용도
    @Bean
    fun anonymousKeyResolver(): KeyResolver = KeyResolver { Mono.just("anonymous") }

    /**
     * 에이전트 토큰 교환의 토큰별 버킷 키. 제출된 토큰의 SHA-256 해시를 쓰므로 같은 토큰의 반복 요청만 한 버킷을 쓴다.
     * 해시는 Authorization이 저장하는 token_hash와 같은 값이고, 토큰 원문은 Redis 키에 남지 않는다.
     * 헤더가 없는 요청은 하나의 버킷을 같이 쓴다. Authorization이 이 요청을 400으로 거부한다.
     *
     * 한계: 형식이 잘못된 무작위 토큰은 요청마다 새 버킷을 쓰므로 이 버킷에 걸리지 않는다. 그런 요청이 몰리면
     * [agentTokenExchangeGlobalRateLimiter]의 전체 상한에 걸려 정상 교환도 429를 받을 수 있다.
     * 입구 프록시가 실제 발신 주소를 넘기지 않는 동안에는 요청자별로 막을 방법이 없다.
     */
    @Bean
    fun agentTokenExchangeKeyResolver(): KeyResolver = KeyResolver { exchange ->
        val token = exchange.request.headers.getFirst(AuthHeaderMutatingFilter.AGENT_TOKEN_HEADER)
        Mono.just(if (token.isNullOrEmpty()) "agent-token:missing" else "agent-token:${sha256Hex(token)}")
    }

    /**
     * 에이전트 토큰 교환 라우트의 전체 상한. 토큰별 버킷 뒤에 두어 과부하 보호에만 쓴다.
     * 같은 라우트의 토큰별 한도와 섞이지 않게 라우트 인자 대신 이 값을 고정한다.
     */
    @Bean
    fun agentTokenExchangeGlobalRateLimiter(
        redisTemplate: ReactiveStringRedisTemplate,
        @Qualifier(RedisRateLimiter.REDIS_SCRIPT_NAME) script: RedisScript<List<Long>>,
        configurationService: ConfigurationService,
        meterRegistry: MeterRegistry,
    ): RedisRateLimiter = ObservedRedisRateLimiter(
        redisTemplate,
        script,
        configurationService,
        meterRegistry,
        mapOf(
            AuthHeaderMutatingFilter.AGENT_TOKEN_EXCHANGE_ROUTE_ID to RedisRateLimiter.Config()
                .setReplenishRate(AGENT_TOKEN_EXCHANGE_GLOBAL_REPLENISH_RATE)
                .setBurstCapacity(AGENT_TOKEN_EXCHANGE_GLOBAL_BURST_CAPACITY)
                .setRequestedTokens(1),
        ),
    )

    /**
     * Rate Limiting 키 전략:
     * 인증된 요청 → X-User-Id 기준 (AuthHeaderMutatingFilter가 JWT 검증 결과로 덮어쓰고, 미인증 요청에서는 제거한다)
     * 미인증 요청  → 연결 상대 주소(remoteAddress) 기준
     *
     * 입구 프록시(GSM SV)는 실제 발신 IP를 전달할 수 없어 미인증 요청은 모두 같은 키로 한도를 공유한다.
     * 클라이언트가 바꿀 수 있는 X-Forwarded-For는 신뢰하지 않는다.
     *
     * 자동 구성에 주입할 기본 KeyResolver를 @Primary로 지정하며, 웹훅 라우트는 webhookKeyResolver를,
     * 토큰 발급/갱신과 GitHub 설치 콜백 라우트는 anonymousKeyResolver를 명시해 사용한다.
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

    companion object {
        // 로그인 시작/코드 교환/토큰 갱신의 전체 상한(authorization-token 라우트)과 같은 값이다.
        // 교환은 색인 조회 한 번, 갱신 한 번, HMAC 서명 한 번이라 로그인보다 가볍다.
        private const val AGENT_TOKEN_EXCHANGE_GLOBAL_REPLENISH_RATE = 50
        private const val AGENT_TOKEN_EXCHANGE_GLOBAL_BURST_CAPACITY = 200L

        private fun sha256Hex(value: String): String = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
        )
    }
}
