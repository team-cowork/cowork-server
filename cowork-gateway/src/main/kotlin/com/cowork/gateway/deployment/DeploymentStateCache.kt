package com.cowork.gateway.deployment

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.ReactiveHealthIndicator
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Configuration
@EnableScheduling
@EnableConfigurationProperties(DeploymentProperties::class)
internal class DeploymentConfiguration

@Component
class DeploymentStateCache(
    private val redis: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val confirmed = AtomicReference<Map<String, DeploymentState>?>(null)
    private val failing = AtomicBoolean(false)

    fun snapshot(): Map<String, DeploymentState>? = confirmed.get()

    @Scheduled(fixedDelay = 1000, initialDelay = 0)
    fun refresh() {
        try {
            val rows = redis.opsForHash<String, String>().entries(REDIS_KEY)
                .collectMap({ it.key }, { it.value })
                .block(Duration.ofSeconds(3)) ?: return
            val states = rows.mapValues { (service, raw) ->
                require(service.matches(Regex("cowork-[a-z-]+")))
                objectMapper.readValue(raw, DeploymentState::class.java)
            }
            confirmed.set(states)
            if (failing.getAndSet(false)) logger.info("Recovered deployment state refresh")
        } catch (error: Exception) {
            // Keep the last confirmed state if Redis or the payload is unavailable.
            meterRegistry.counter("gateway.deployment.state.refresh.failures").increment()
            if (!failing.getAndSet(true)) {
                logger.warn("Failed to refresh deployment state: {}", error.javaClass.simpleName)
            }
        }
    }

    companion object {
        const val REDIS_KEY = "cowork:deployment:services"
    }
}

@Component("deploymentState")
class DeploymentStateHealthIndicator(private val cache: DeploymentStateCache) : ReactiveHealthIndicator {
    override fun health(): Mono<Health> = Mono.just(
        if (cache.snapshot() == null) Health.down().build() else Health.up().build(),
    )
}
